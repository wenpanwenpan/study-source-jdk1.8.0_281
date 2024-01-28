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

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    // 表示当前task的状态
    private volatile int state;
    // 当前任务尚未执行
    private static final int NEW          = 0;
    // 当前任务正在结束，稍微完全结束（这是一种临界状态）
    private static final int COMPLETING   = 1;
    // 当前任务正常结束
    private static final int NORMAL       = 2;
    // 当前任务执行过程中发生了异常。
    private static final int EXCEPTIONAL  = 3;
    // 当前任务被取消
    private static final int CANCELLED    = 4;
    // 当前任务中断中
    private static final int INTERRUPTING = 5;
    // 当前任务已中断
    private static final int INTERRUPTED  = 6;

    /** The underlying callable; nulled out after running */
    // submit(callable / runnable) runnable接口使用适配器模式适配成了 callable
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    // 任务正常执行情况下：outcome 保存了任务的执行返回结果，即callable.call的返回值
    // 任务异常执行情况下：outcome 保存了任务执行时抛出的异常, 即 callable.call 方法抛出的异常
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    // 当前任务被线程执行期间，保存当前执行任务的线程对象引用
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        // 获取 outcome 的值，正常执行的情况下 outcome 保存的是 callable.call运行的返回值
        // 获取 outcome 的值，异常执行的情况下 outcome 保存的是 callable.call运行抛出的异常
        Object x = outcome;
        // 如果任务是正常执行结束，则直接返回 callable.call 方法的返回结果
        if (s == NORMAL)
            return (V)x;
        // 如果任务是非正常执行结束，则抛出被取消异常
        if (s >= CANCELLED)
            throw new CancellationException();
        // 如果执行到这里，则说明 callable.call 方法实现有bug了
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        // 如果新建 FutureTask 对象的时候传入的是 runnable ，则通过 Executors.callable(runnable, result)将 runnable 适配为 callable
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    // runner 表示执行当前FutureTask任务的线程。有可能是null，是null的情况是：当前任务还在等待队列中，还没有线程去取他出来执行
                    Thread t = runner;
                    // 条件成立，则说明当前任务已经正在被执行了。可以被中断，则给执行任务的线程一个中断信号（若你的程序（callable.call方法）是不响应中断的，
                    // 则啥也不会发生，如果你的程序是响应中断的，则进行相应的中断处理）
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    // 设置任务状态为 中断完成。
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            // 唤醒外部所有调用get（）方法获取任务返回值的线程
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     * 该方法是外部线程调用 get
     * 可能会有 多个线程 等待着get这个任务执行的返回值
     * 场景：多个线程等待当前任务执行完后的结果....
     */
    public V get() throws InterruptedException, ExecutionException {
        // 获取当前任务状态
        int s = state;
        // 如果 s <= COMPLETING ，则说明未执行、正在执行、正在完成（总之就是当前任务还没执行结束）
        // 则外部想要获取到当前任务的返回结果的外部线程会被阻塞在这里
        if (s <= COMPLETING)
            // 不带超时的等待，返回当前任务的状态
            s = awaitDone(false, 0L);
        // 返回 callable.call 方法的执行结果
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        // 获取当前任务状态
        int s = state;
        // awaitDone(true, unit.toNanos(timeout)): 带超时的等待，返回当前任务的状态
        // 如果 s <= COMPLETING ，则说明未执行、正在执行、正在完成（总之就是当前任务还没执行结束）
        // 则外部想要获取到当前任务的返回结果的外部线程会被阻塞在这里
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        // 返回 callable.call 方法的执行结果
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        // 使用cas的方式设置当前任务状态为 完成中....
        // 有没有可能设置不成功呢？ 有，比如外部线程等不及了，直接在set 执行cas之前将task取消了（小概率）
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 将线程执行的返回结果，赋值给 outcome 变量
            outcome = v;
            // 将当前任务状态修改为 nomal 正常结束状态。
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            // 稍后再看
            // 猜测：至少这里会将外部调用 get（） 方法想获取返回值的线程给唤醒
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        // 使用cas的方式设置当前任务状态为 完成中....
        // 有没有可能设置不成功呢？ 有，比如外部线程等不及了，直接在set 执行cas之前将task取消了（小概率）
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 将异常信息设置到 outcome 中(这里的异常是 callable 接口向上抛出的异常)
            outcome = t;
            // 将当前任务状态修改为 EXCEPTIONAL（异常状态）
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            // 一会儿再看
            finishCompletion();
        }
    }

    public void run() {
        // 条件一：若 state != NEW 成立，则说明当前 task 已经被执行过了，或者已经被取消了，总之就是，非 NEW 状态的任务，线程就不处理了
        // !UNSAFE.compareAndSwapObject(this, runnerOffset,null, Thread.currentThread()) 条件成立，则说明 cas 设置 runnerOffset
        // 失败，说明当前任务被其他线程抢占了。
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        // 执行到这里，说明当前状态一定是 NEW 状态，并且当前线程抢占 Task 也成功
        try {
            Callable<V> c = callable;
            // c != nul 防止空指针异常
            // state == NEW 防止外部线程取消掉当前任务
            if (c != null && state == NEW) {
                // 结果引用
                V result;
                // true 表示 callable.run 代码块执行成功，未抛出异常
                // false 表示 callable.run 代码块执行失败，抛出异常
                boolean ran;
                try {
                    // 调用我们自己实现的callable接口的call，或是经过适配后的runnable接口的call方法
                    result = c.call();
                    // 执行成功则修改 ran 状态为 true
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    // 执行失败则修改 ran 状态为 false
                    ran = false;
                    // 设置异常信息，将异常信息封装到 outcome 中
                    setException(ex);
                }
                // 如果 ran = true 则说明 c.call（）代码正常执行结束了
                if (ran)
                    // 设置线程执行返回结果到outcome属性中
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        // waiters 指向等待队列的头节点
        // q 指向等待队列的头节点
        for (WaitNode q; (q = waiters) != null;) {
            // 使用 cas 设置 waiters 为null ，是因为怕外部线程使用 cancel 取消当前任务（也会触发 finishCompletion（） 方法的调用）
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    // 获取到q节点代表的线程
                    Thread t = q.thread;
                    if (t != null) {
                        // 走到这里，则说明当前节点所代表的thread不为空，则将node.thread 置为空（help GC）
                        q.thread = null;
                        // 叫醒等待的线程（即唤醒当前这个节点对应的线程）
                        LockSupport.unpark(t);
                    }
                    // 获取到当前节点的下一个节点
                    WaitNode next = q.next;
                    // 如果next == null 则说明等待队列的节点都被处理完了，则直接跳出去结束双重for循环
                    if (next == null)
                        break;
                    // 将当前节点的next指针置空，方便当前节点被 GC 回收
                    q.next = null; // unlink to help gc
                    // 如果 q.next != null 则继续处理 q.next 节点
                    q = next;
                }
                break;
            }
        }

        // 空方法，留给子类扩展
        done();
        // help GC
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     * 该方法用于当外部线程想要获取任务的执行结果的时候，如果当前任务还没有执行完毕，则将外部线程加入到等待队列中
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        // 0 表示不带超时
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        // 引用当前线程，封装成 WaitNode 对象
        WaitNode q = null;
        // 表示当前线程WaitNode对象没有入队
        boolean queued = false;

        // 自旋
        for (;;) {
            // 如果 Thread.interrupted() 条件成立，则说明当前线程是被其他线程使用 中断这种方式 唤醒的
            // interrupted（）方法返回true后，会将Thread中的中断标记重置回false
            if (Thread.interrupted()) {
                // 将当前外部线程的node从等待队列中移除。
                removeWaiter(q);
                // 向外部调用这个方法的方法抛出中断异常
                throw new InterruptedException();
            }

            // 加入当前线程是被其他线程使用 unpark（thread）的方式唤醒的，会正常自旋，走下面的逻辑
            int s = state;
            // 条件成立，则说明当前任务已经有了结果。可能是好结果也可能是异常.....
            if (s > COMPLETING) {
                // 条件成立，则说明已经为当前线程创建过node了，此时需要 q.thread = null （help GC）
                if (q != null)
                    q.thread = null;
                // 直接返回当前状态
                return s;
            }
            // 当前任务接近完成，或接近失败状态。这里让当前线程再次释放cpu，进入到下一次cpu争抢....
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            // 第一次自旋进入这里，为外部调用get()方法获取结果的线程创建一个node，将线程封装为node
            else if (q == null)
                q = new WaitNode();
            // 第二次自旋进入这里，如果该线程对应的node节点还没有入队，则使用cas的方式入队（waiters一直指向等待队列的头节点）
            else if (!queued){
                // 入队
                q.next = waiters;
                // 返回是否入队成功
                // 这里 waiters 指向等待队列头节点，上一步已经将 q.next 指向了 waiters 了，这里是通过cas的方式将 waiters 和 q交换
                // 使用cas的方式将 waiters 指向当前节点，如果成功了则返回true，如果失败了则说明可能其他线程已经先一步将waiters指向他的node了，需要重试
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset, waiters, q);
            }
            // 第三次自旋进入这里（如果是带等待时间的）
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                // 这里只将线程 park nanos 时间
                LockSupport.parkNanos(this, nanos);
            }
            // 当前执行get操作的线程就会被park掉，线程状态就会变成 waitting 状态了，相当于休眠了.....
            // 除非有其他的线程将你唤醒，或将当前线程中断。注意和上面一句代码对比
            else
                // 将线程park掉
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    // 任务状态
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
