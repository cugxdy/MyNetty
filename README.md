# MyNetty
1、netty 中由ChannelHandlerContext发起的异步写机制与垃圾回收？
            AbstractWriteTask task;
            if (flush) {
            	// 写同时进行flush任务
                task = WriteAndFlushTask.newInstance(next, msg, promise);
            }  else {
            	// 写任务
                task = WriteTask.newInstance(next, msg, promise);
            }
            safeExecute(executor, task, promise, msg);\n
2、netty 中如何处理selector优化技巧\n
3、netty 中如何处理Epoll 空轮询bug 导致CPU100%
