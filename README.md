# fixedThreadPoolPlus
支持在线程池中线程耗尽的情况下，将线程池中所有线程当前正在执行那条代码的信息汇总并打印出来，
同时将线程全部的完整堆栈信息保存到用户目录下的 "线程名.detail" 文件中，方便定位及排查线程池耗尽时的现场环境，目前只支持Linux

信息包含类似如下的提示(如果是10个线程的线程池):

Exception in thread "main" java.util.concurrent.RejectedExecutionException: Thread pool is EXHAUSTED! Thread Name: test-thredpool-plus, Pool Size: 10 (active: 10, core: 10, max: 10, largest: 10), Task: 10 (completed: 0), Executor status:(isShutdown:false, isTerminated:false, isTerminating:false)

6 threads are hanging on this code : 	at java.net.PlainSocketImpl.socketConnect(Native Method)
4 threads are hanging on this code : 	at java.lang.Thread.sleep(Native Method)
You can fetch more thread statck information by the detail file : /Users/yuekuo/test-thredpool-plus.detail

测试代码请参加wiki中的demo
