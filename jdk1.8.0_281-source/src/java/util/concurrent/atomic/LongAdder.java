/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;
import java.io.Serializable;

/**
 * One or more variables that together maintain an initially zero
 * {@code long} sum.  When updates (method {@link #add}) are contended
 * across threads, the set of variables may grow dynamically to reduce
 * contention. Method {@link #sum} (or, equivalently, {@link
 * #longValue}) returns the current total combined across the
 * variables maintaining the sum.
 *
 * <p>This class is usually preferable to {@link AtomicLong} when
 * multiple threads update a common sum that is used for purposes such
 * as collecting statistics, not for fine-grained synchronization
 * control.  Under low update contention, the two classes have similar
 * characteristics. But under high contention, expected throughput of
 * this class is significantly higher, at the expense of higher space
 * consumption.
 *
 * <p>LongAdders can be used with a {@link
 * java.util.concurrent.ConcurrentHashMap} to maintain a scalable
 * frequency map (a form of histogram or multiset). For example, to
 * add a count to a {@code ConcurrentHashMap<String,LongAdder> freqs},
 * initializing if not already present, you can use {@code
 * freqs.computeIfAbsent(k -> new LongAdder()).increment();}
 *
 * <p>This class extends {@link Number}, but does <em>not</em> define
 * methods such as {@code equals}, {@code hashCode} and {@code
 * compareTo} because instances are expected to be mutated, and so are
 * not useful as collection keys.
 *
 * @since 1.8
 * @author Doug Lea
 */
public class LongAdder extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    /**
     * Creates a new adder with initial sum of zero.
     */
    public LongAdder() {
    }

    /**
     * Adds the given value.
     *
     * @param x the value to add
     *          该方法需要重点探究
     */
    public void add(long x) {
        // as 表示cells的引用
        // b 表示base值
        // v 表示期望值
        // m 表示cells数组的长度
        // a 表示当前线程命中的cell单元格
        Cell[] as; long b, v; int m; Cell a;
        // ===========================================================================================
        // 条件一：(as = cells) != null 为true：表示cells已经被初始化过了，当前线程应该把值写入到对应的cell中。
        //                             为false：表示cells未初始化，当前所有线程应该将数据写入到base中
        // 条件二：!casBase(b = base, b + x) 为true：表示可能发生了竞争，可能需要重试或扩容。
        //                                  为false：表示当前线程cas替换数据成功
        // ===========================================================================================
        if ((as = cells) != null || !casBase(b = base, b + x)) {
            // 什么时候会进来呢？
            // 1、cells已经被初始化过了，当前线程应该把值写入到对应的cell中
            // 2、cells还没被初始化过,数据直接累加到base，但是在写base的时候发生了竞争，可能需要 重试 或 扩容。

            // uncontended = true : 表示未发生竞争。 uncontended = false ： 表示发生了竞争
            boolean uncontended = true;

            // ===========================================================================================
            // 条件一（as == null || (m = as.length - 1) < 0 ）：
            // 为true则说明cells未初始化，也就是多线程写base发生了竞争。
            // 为false则说明cells已经初始化了，当前线程应该是找自己的cell写值

            // 条件二（(a = as[getProbe() & m]) == null）：
            // getProbe()表示获取当前线程的hash值， m表示cells长度 - 1（cells的长度
            // 一定是2的次方数，减一后二进制表示一定为 1111111，全是1）。
            // 为 true 则说明当前线程对应下标的cell为空，需要创建 longAccumulate 支持
            // 为 false 则说明当前线程的对应的cell不为空，说明下一步想要将x值添加到cell中

            // 条件三（!(uncontended = a.cas(v = a.value, v + x))）：
            // 为false表示cas设置值到cell上失败，意味着当前线程对应的cell有竞争。为true表示cas设置值到该cell上成功
            // ===========================================================================================
            if (as == null || (m = as.length - 1) < 0 ||
                (a = as[getProbe() & m]) == null ||
                !(uncontended = a.cas(v = a.value, v + x)))
                // ===========================================================================================
                // 重点探究
                // 有哪些情况会走到这里？
                // 1、cells未初始化（说明此刻之前所有的线程都将数据直接写到的base），也就是多线程写base发生了竞争。
                // 2、当前线程对应下标的cell为空，需要创建 longAccumulate 支持
                // 3、cas设置值到cell上失败，意味着当前线程对应的cell有竞争（表示此时有多个线程在往该cell上设置值）。
                // ===========================================================================================
                longAccumulate(x, null, uncontended);
        }
    }

    /**
     * Equivalent to {@code add(1)}.
     * 自增1
     */
    public void increment() {
        add(1L);
    }

    /**
     * Equivalent to {@code add(-1)}.
     * 自减1
     */
    public void decrement() {
        add(-1L);
    }

    /**
     * Returns the current sum.  The returned value is <em>NOT</em> an
     * atomic snapshot; invocation in the absence of concurrent
     * updates returns an accurate result, but concurrent updates that
     * occur while the sum is being calculated might not be
     * incorporated.
     * 求和，获取这个 longAdapter 的值（base + 每个cell的值）。
     * 注意：不一定准确，因为可能在求和的过程中还有其他线程对这个值进行增减
     * @return the sum
     */
    public long sum() {
        Cell[] as = cells; Cell a;
        long sum = base;
        // 依次将 base 加上每个cell的值
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    /**
     * Resets variables maintaining the sum to zero.  This method may
     * be a useful alternative to creating a new adder, but is only
     * effective if there are no concurrent updates.  Because this
     * method is intrinsically racy, it should only be used when it is
     * known that no threads are concurrently updating.
     * 重置cells，将cells中的每个cell格子都置为0
     */
    public void reset() {
        Cell[] as = cells; Cell a;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    a.value = 0L;
            }
        }
    }

    /**
     * Equivalent in effect to {@link #sum} followed by {@link
     * #reset}. This method may apply for example during quiescent
     * points between multithreaded computations.  If there are
     * updates concurrent with this method, the returned value is
     * <em>not</em> guaranteed to be the final value occurring before
     * the reset.
     * 先求和然后再重置
     * @return the sum
     */
    public long sumThenReset() {
        Cell[] as = cells; Cell a;
        long sum = base;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null) {
                    sum += a.value;
                    a.value = 0L;
                }
            }
        }
        return sum;
    }

    /**
     * Returns the String representation of the {@link #sum}.
     * @return the String representation of the {@link #sum}
     * 将longAdapter求和后变为字符串
     */
    public String toString() {
        return Long.toString(sum());
    }

    /**
     * Equivalent to {@link #sum}.
     *
     * @return the sum
     * 获取longAdapter的值
     */
    public long longValue() {
        return sum();
    }

    /**
     * Returns the {@link #sum} as an {@code int} after a narrowing
     * primitive conversion.
     * 将longAdapter的值强转为int类型
     */
    public int intValue() {
        return (int)sum();
    }

    /**
     * Returns the {@link #sum} as a {@code float}
     * after a widening primitive conversion.
     * 将longAdapter的值强转为float类型
     */
    public float floatValue() {
        return (float)sum();
    }

    /**
     * Returns the {@link #sum} as a {@code double} after a widening
     * primitive conversion.
     * 将longAdapter的值强转为 double 类型
     */
    public double doubleValue() {
        return (double)sum();
    }

    /**
     * Serialization proxy, used to avoid reference to the non-public
     * Striped64 superclass in serialized forms.
     * @serial include
     */
    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        /**
         * The current value returned by sum().
         * @serial
         */
        private final long value;

        SerializationProxy(LongAdder a) {
            value = a.sum();
        }

        /**
         * Return a {@code LongAdder} object with initial state
         * held by this proxy.
         *
         * @return a {@code LongAdder} object with initial state
         * held by this proxy.
         */
        private Object readResolve() {
            LongAdder a = new LongAdder();
            a.base = value;
            return a;
        }
    }

    /**
     * Returns a
     * <a href="../../../../serialized-form.html#java.util.concurrent.atomic.LongAdder.SerializationProxy">
     * SerializationProxy</a>
     * representing the state of this instance.
     *
     * @return a {@link SerializationProxy}
     * representing the state of this instance
     */
    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    /**
     * @param s the stream
     * @throws java.io.InvalidObjectException always
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }

}
