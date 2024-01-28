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
import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {
    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    @sun.misc.Contended static final class Cell {
        volatile long value;
        Cell(long x) { value = x; }
        // 通过cas的方式将数据累加到value
        final boolean cas(long cmp, long val) {
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        // cell对象中，value的偏移量
        private static final long valueOffset;
        static {
            // 初始化 UNSAFE 和 valueOffset
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Number of CPUS, to place bound on table size */
    // 当前计算机 CPU 数量，什么用？ 控制cells数组的长度的一个关键条件
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Table of cells. When non-null, size is a power of 2.
     */
    transient volatile Cell[] cells;

    /**
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     */
    // 没有发生过竞争的时候，数据会累加到base上，或 当cells扩容时，需要将数据写入到base中。
    transient volatile long base;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     */
    // 只要要初始化或扩容cells时需要获取这把锁。 0 表示无锁状态，1 表示其他线程已经持有锁了
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.
     * 通过cas的方式去修改 this对象的 BASE 偏移量上的数据值，期望值为 cmp ,新值为 val
     * 即：通过cas的方式去修改 base 的值
     */
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.
     * 通过cas的方式去获取锁
     */
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     * 获取当前线程的hash值
     */
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     * 重置当前线程的hash值，由该值决定当前线程应该去哪个cells
     */
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x the value
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     *                       todo 重点探究方法
     *
     * wasUncontended : 只有cells初始化后，且当前线程竞争修改cells失败才会返回false
     */
    final void longAccumulate(long x, LongBinaryOperator fn,
                              boolean wasUncontended) {
        // 当前线程的hash值
        int h;
        // (h = getProbe()) == 0 ： 即当前线程还没分配hash值
        if ((h = getProbe()) == 0) {
            // 给当前线程分配hash值
            ThreadLocalRandom.current(); // force initialization
            // 取出当前线程的hash值赋值给h
            h = getProbe();
            // 为什么？因为默认情况下当前线程肯定是写入到了 cells[0] 这个位置，不把他当做一次真正的竞争
            wasUncontended = true;
        }
        // 扩容意向，false ： 一定不会扩容。 true ： 可能会扩容
        boolean collide = false;                // True if last slot nonempty
        // 自旋
        for (;;) {
            // case-1 : 表示cells已经被初始化了，当前线程需要将数据写入到 cells中
            // as ：表示cells引用
            // a : 当前线程命中的cell
            // n : cells数组长度
            // v ： 期望值
            Cell[] as; Cell a; int n; long v;
            // (as = cells) != null : 表示 cells已经被初始化了，当前线程应该将数据写入到cells中
            // (n = as.length) > 0 : 表示 cells 已经被初始化
            if ((as = cells) != null && (n = as.length) > 0) {
                // case-1.1
                // 条件true，表示当前线程对应的cells下标位置的cell为空，需要创建 new cell
                if ((a = as[(n - 1) & h]) == null) {
                    // 锁没有被占用
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        // 创建一个cell
                        Cell r = new Cell(x);   // Optimistically create
                        // 获取锁，将 cellsBusy 设置为 1
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                // rs : cells 的引用
                                // m ： 表示当前cells长度
                                // j ： 当前线程命中的cells中的下标
                                Cell[] rs; int m, j;
                                // (rs = cells) != null && (m = rs.length) > 0 条件恒成立
                                // rs[j = (m - 1) & h] 和上面的 as[(n - 1) & h]) 一样，预防多线程问题（上个线程已经给cells中的这个格子放入值了，
                                // 但这里又再次初始化，导致第一个线程的数据丢失）
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    // 给 cells[j] 位置赋一个值
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                // 释放锁
                                cellsBusy = 0;
                            }
                            // 创建成功则退出自旋
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    // 扩容意向强制改成了false
                    collide = false;
                }
                // case-1.2 只有一种情况会走这里： 只有cells初始化后，且当前线程竞争修改cells失败才会返回false
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                 // case-1.3 当前线程rehash过hash值，当前新命中的cell不为空
                // cas写成功，则退出自旋；写失败，则说明重试依次rehash后cell中仍然有数据
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    break;
                // case-1.4
                // 条件一 n >= NCPU ： cells数组长度大于CPU核数，则将扩容意向改为false，表示不扩容了。false：说明数组还可以扩容
                // cells != as : 表示其他线程已经扩容过了，当前线程只要再次rehash重试即可
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                // case-1.5 ： 如果进入了 1.4，则一定会进入这里，collide = true;设置扩容意向为true，但是不一定需要扩容
                else if (!collide)
                    collide = true;
                // case-1.6 ：真正扩容的逻辑。
                // cellsBusy == 0 && casCellsBusy() : 为true表示当前无锁状态，并且当前线程竞争到这把锁，可以执行扩容逻辑
                // 两个线程A、B 同时到达cellsBusy == 0 && casCellsBusy()的第一个条件。此时A由于某种原因让出CPU，B正常执行完毕
                // 并且释放锁，A再次得到CPU的时候可能会获取到锁，然后执行里面的逻辑，所以里面需要再次判断 cells == as
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        // 再次判断，防止多线程情况下cells已经被扩容过了
                        if (cells == as) {      // Expand table unless stale
                            // 扩容并且将旧数据赋值到新数组中
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            // 变更cells的引用
                            cells = rs;
                        }
                    } finally {
                        // 释放锁
                        cellsBusy = 0;
                    }
                    // 设置不需要再扩容了
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                // 重置当前线程hash值
                h = advanceProbe(h);
            }
            // case2 : 前置条件 cells 还没有被初始化 as 为空
            // cellsBusy == 0 表示当前未加锁
            // cells == as : 再次对比，多线程其他线程可能已经去初始化 cells 了
            // casCellsBusy() : 如果为true表示获取锁成功，获取锁成功时会把 casCellsBusy 设置成 1，失败，则说明其他线程已经获取到了锁
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    // cells == as ： 考虑多线程，多个线程都执行到了上面的else if 的前两个判断条件（即 cellsBusy == 0 && cells == as）
                    // 防止其他线程已经初始化了，这里再次初始化，丢数据
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            // case-3 :
            // 当前 cellsBusy 处于加锁状态会来到这里。表示其他线程正在初始化cells，当前线程需要将值累加到base
            // cells被其他线程初始化了，当前线程需要将数据累加到 base
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                break;                          // Fall back on using base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
