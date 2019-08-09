# MyNetty
1、netty 中由ChannelHandlerContext发起的异步写机制与垃圾回收？</br>
            AbstractWriteTask task;</br>
            if (flush) {</br>
            	// 写同时进行flush任务</br>
                task = WriteAndFlushTask.newInstance(next, msg, promise);</br>
            }  else {</br>
            	// 写任务</br>
                task = WriteTask.newInstance(next, msg, promise);</br>
            }</br>
            safeExecute(executor, task, promise, msg);</br>
2、netty 中如何处理selector优化技巧</br>
3、netty 中如何处理Epoll 空轮询bug 导致CPU100%
