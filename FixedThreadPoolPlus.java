package liq.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
* 支持在线程池中线程耗尽的情况下，将线程池中所有线程当前正在执行那条代码的信息汇总，作为异常信息抛出，
* 同时将线程全部的完整堆栈信息保存到用户目录下的 "线程名.detail" 文件中，方便定位及排查线程池耗尽时的现场环境，目前只支持Linux
* 信息包含类似如下的提示(如果是10个线程的线程池):
* 
* Exception in thread "main" java.util.concurrent.RejectedExecutionException: Thread pool is EXHAUSTED! Thread Name: test-thredpool-plus, Pool Size: 10 (active: 10, core: 10, max: 10, largest: 10), Task: 10 (completed: 0), Executor status:(isShutdown:false, isTerminated:false, isTerminating:false)
* 6 threads are hanging on this code : at java.net.PlainSocketImpl.socketConnect(Native Method)
* 4 threads are hanging on this code : at java.lang.Thread.sleep(Native Method)
* You can fetch more thread statck information by the detail file : /Users/yuekuo/test-thredpool-plus.detail
*
* @author yuekuo.liq
 */
public class FixedThreadPoolPlus {
    /**
     * @param nThreads 线程个数
     * @param queues 允许缓冲在队列中的任务数 (0:不缓冲、负数：无限大、正数：缓冲的任务数)
     * @param threadName 该线程组中线程的命
     * @param processNameMatch 应用的java进程名,支持模糊匹配
     *            (当机器上跑多个java进程时，只打印出指定进程的线程堆栈,你可用通过 jps -l 命令找出你java应用的进程名)
     * @return Executor
     */
    public static Executor newFixedThreadPools(final int nThreads, final int queues,
                                               final String threadName,
                                               final String processNameMatch) {
        final ThreadPoolExecutor ex = new ThreadPoolExecutor(nThreads, nThreads, 0L,
                TimeUnit.MILLISECONDS, queues == 0 ? new SynchronousQueue<Runnable>()
                        : (queues < 0 ? new LinkedBlockingQueue<Runnable>()
                                : new LinkedBlockingQueue<Runnable>(queues)), new ThreadFactory() {
                    private AtomicInteger threadCount = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName(threadName + "-" + threadCount.getAndIncrement());
                        return thread;
                    }
                });
        ex.setRejectedExecutionHandler(new AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                String home = System.getProperty("user.home");
                String fileName = home + "/" + threadName + ".detail";
                String os = System.getProperty("os.name").toLowerCase();
                String jcmd = "jps -l|awk '/" + processNameMatch + "/{print $1}'| xargs jstack";
                String[] cmds;
                if (os.startsWith("win")) {
                    throw new UnsupportedOperationException(
                            "does not support windows platform, please change to linux");
                } else {
                    //like linux
                    cmds = new String[] { "/bin/sh", "-c", jcmd };
                }

                BufferedReader reader = null;
                FileWriter fileWriter = null;
                Process process = null;
                try {
                    process = Runtime.getRuntime().exec(cmds);
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    fileWriter = generateDetailFile(threadName, fileName);
                    HashMap<String, Integer> hashMap = new HashMap<String, Integer>();
                    String lineMessage;
                    while (true) {
                        lineMessage = reader.readLine();
                        //全部信息处理完，则结束
                        if (lineMessage == null) {
                            break;
                        }
                        //只找该线程池中的线程做统计，其他的线程跳过
                        if (!lineMessage.contains(threadName)) {
                            continue;
                        }

                        boolean firstAt = true;
                        while (true) {
                            // 线程堆栈中的第一行，即线程挂住时运行到的代码
                            if (lineMessage.trim().startsWith("at") && firstAt) {
                                Integer count = hashMap.get(lineMessage);
                                hashMap.put(lineMessage, count == null ? 1 : count + 1);
                                firstAt = false;
                            }
                            //如何当前线程堆栈结束(每个线程堆栈之间有空行区分)，则跳出本次循环
                            if ("".equals(lineMessage.trim())) {
                                write2DetailFile(fileWriter, "\n");
                                break;
                            }

                            write2DetailFile(fileWriter, lineMessage);

                            lineMessage = reader.readLine();
                        }
                    }
                    String msg = String
                            .format("Thread pool is EXHAUSTED!"
                                    + " Thread Name: %s, Pool Size: %d (active: %d, core: %d, max: %d, largest: %d), Task: %d (completed: %d),"
                                    + " Executor status:(isShutdown:%s, isTerminated:%s, isTerminating:%s)",
                                    threadName, ex.getPoolSize(), ex.getActiveCount(),
                                    ex.getCorePoolSize(), ex.getMaximumPoolSize(),
                                    ex.getLargestPoolSize(), ex.getTaskCount(),
                                    ex.getCompletedTaskCount(), ex.isShutdown(), ex.isTerminated(),
                                    ex.isTerminating())
                            + "\n";

                    TreeMap<String, Integer> sortMap = new TreeMap<String, Integer>(
                            new ComparetorImpl<String>(hashMap));
                    sortMap.putAll(hashMap);

                    for (Iterator<Map.Entry<String, Integer>> iterator = sortMap.entrySet()
                            .iterator(); iterator.hasNext();) {
                        Map.Entry<String, Integer> entry = iterator.next();
                        msg = msg + entry.getValue() + " " + "threads are hanging on this code : "
                                + entry.getKey() + "\n";
                    }

                    msg = msg
                            + "You can fetch more thread statck information by the detail file : "
                            + fileName + "\n";
                    throw new RejectedExecutionException(msg);
                } catch (IOException e) {
                    throw new RejectedExecutionException(e);
                } finally {
                    try {
                        if (reader != null) {
                            reader.close();
                        }
                        if (fileWriter != null) {
                            fileWriter.close();
                        }
                    } catch (IOException e) {
                        throw new RejectedExecutionException(e);
                    }
                    process.destroy();
                }
            }
        });
        return ex;
    }

    private static FileWriter generateDetailFile(String threadName, String fileName)
            throws IOException {
        File detailFile = new File(fileName);
        if (!detailFile.exists()) {
            detailFile.createNewFile();
        }
        FileWriter fileWriter = new FileWriter(detailFile);
        return fileWriter;
    }

    private static void write2DetailFile(FileWriter fileWriter, String lineMessage)
            throws IOException {
        fileWriter.write(lineMessage + "\n");
    }

    //根据线程挂在哪一行代码上的次数降序排列
    private static class ComparetorImpl<K> implements Comparator<K> {
        private HashMap<String, Integer> hashMap;

        public ComparetorImpl(HashMap<String, Integer> hashMap) {
            this.hashMap = hashMap;
        }

        @Override
        public int compare(K key1, K key2) {
            int count1 = hashMap.get(key1);
            int count2 = hashMap.get(key2);
            if (count1 > count2) {
                return -1;
            } else if (count1 < count2) {
                return 1;
            }
            return 0;
        }

    }

}
