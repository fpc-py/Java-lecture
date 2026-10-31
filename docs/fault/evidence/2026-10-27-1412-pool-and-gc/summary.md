# 取证摘要 · pool-and-gc

| 项 | 值 |
|---|---|
| 采集时间 | 2026-10-27 14:12:49 +0800 |
| PID | 39684 |
| jstack 轮次 | 3（间隔 3s） |

## 1. 线程状态分布（第 1 轮）

```
    112 TIMED_WAITING
     82 WAITING
     51 RUNNABLE
```

> BLOCKED 数量 > 0 说明有锁竞争；某状态在 3 轮里始终不降，说明线程真的卡住了。

## 2. 各线程池占用（按线程名前缀统计）

```
前缀        第1轮 第2轮 第3轮
biz-price         0     0     0
biz-stock         0     0     0
biz-rec           8     8     8
http-nio         34    34    34
outbox-relay      0     0     0
```

> 这是判断舱壁是否生效最直接的一屏：
> 如果 `biz-rec` 涨到 8/8 而 `biz-stock` 始终 0，说明隔离成功；
> 如果两列同时涨，说明有人把池子合并了。

## 3. 停在下游 IO 上的线程（3 轮都出现即视为「真卡住」）

```
    116 at sun.nio.ch.WEPollSelectorImpl.doSelect(java.base@21.0.12.1/WEPollSelectorImpl.java:114)
    116 at sun.nio.ch.WEPoll.wait(java.base@21.0.12.1/Native Method)
    116 at sun.nio.ch.SelectorImpl.lockAndDoSelect(java.base@21.0.12.1/SelectorImpl.java:130)
      3 at sun.nio.ch.ServerSocketChannelImpl.implAccept(java.base@21.0.12.1/ServerSocketChannelImpl.java:433)
      3 at sun.nio.ch.ServerSocketChannelImpl.accept(java.base@21.0.12.1/ServerSocketChannelImpl.java:399)
      3 at sun.nio.ch.NioSocketImpl.park(java.base@21.0.12.1/NioSocketImpl.java:201)
      3 at sun.nio.ch.NioSocketImpl.park(java.base@21.0.12.1/NioSocketImpl.java:191)
      3 at sun.nio.ch.Net.poll(java.base@21.0.12.1/Native Method)
      3 at sun.nio.ch.Net.accept(java.base@21.0.12.1/Native Method)
      1 at sun.nio.ch.SocketDispatcher.write0(java.base@21.0.12.1/Native Method)
```

## 4. GC 摘要（jstat 末行）

```
  S0     S1     E      O      M     CCS    YGC     YGCT     FGC    FGCT     CGC    CGCT       GCT   
     -  95.39  18.09  24.40  98.89  95.67     19     0.182     0     0.000     8     0.027     0.209
```

> 列含义：YGC/YGCT（Young GC 次数/耗时）、FGC/FGCT（Full GC 次数/耗时）。
> **FGC 增长而堆没满**，基本可以断定有人在调用 System.gc() 或直接内存吃紧。

## 5. 占内存最多的 5 类对象

```
   1:        139375       12377736  [B (java.base@21.0.12.1)
   2:         30440        5135592  [Ljava.lang.Object; (java.base@21.0.12.1)
   3:         41422        3645136  java.lang.reflect.Method (java.base@21.0.12.1)
   4:           814        3440744  [C (java.base@21.0.12.1)
   5:        105206        3366592  java.util.concurrent.ConcurrentHashMap$Node (java.base@21.0.12.1)
```

## 6. 堆外内存（NMT summary）

```
Total: reserved=1406MB, committed=860MB
-                 Java Heap (reserved=512MB, committed=512MB)
-                     Class (reserved=210MB, committed=14MB)
                            (  Class space:)
-                    Thread (reserved=142MB, committed=19MB)
-                      Code (reserved=247MB, committed=63MB)
-                        GC (reserved=65MB, committed=65MB)
-                  Internal (reserved=1MB, committed=1MB)
-                     Other (reserved=32MB, committed=32MB)
```

> 这一段回答的是 ch02 场景 A：**Pod 被 OOMKilled 但 heap 只用了 40%**。
> Total（committed）远大于 Java Heap，差额就是被容器 limit 杀掉的原因。
