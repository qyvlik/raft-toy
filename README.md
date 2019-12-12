# raft-toy


节点主动发起请求有如下特性：

1. 只有 **Leader** 会主动发送 `appendEntries` 给其他节点
2. 只有 **Candidate** 会发送 `requestVote` 给其他节点
3. **Follower** 不会主动发送任何请求给其他节点，除非由于 **Follower** timeout 而切换为 **Candidate**。

节点接受请求有如下特性：
1. 处理请求，只有在 `term = currentTerm` 情况下才可以正常进行处理请求
2. `term > currentTerm`，当前节点必须转换为 **Follower**，然后继续处理请求
3. **Follower** 接受 `appendEntries` 以及 `requestVote`
4. **Candidate** 接受 `appendEntries`，但是不接受 `term <= currentTerm` 的 `requestVote`，因为 **Candidate** 给自身投票了
5. **Leader** 接受 `appendEntries`，是否接受 `requestVote`？
    > 如果 `requestVote` `term > currentTerm`，那么 **Leader** 变为 **Follower**，并进行投票逻辑
    > 如果 `requestVote` `term = currentTerm`，**Leader** 是否需要执行投票逻辑？

## role

### Follower

- 响应来自候选人和领导者的请求
- 如果在超过选举超时时间的情况之前都没有收到领导人的心跳，或者是候选人请求投票的，就自己变成候选人
- 如果接收到的 RPC 请求或响应中，任期号T > currentTerm，那么就令 currentTerm 等于 T，并切换状态为跟随者（5.1 节）
- 如果commitIndex > lastApplied，那么就 lastApplied 加一，并把log[lastApplied]应用到状态机中（5.3 节）

- receive `heartbeat` or `appendEntries`
    1. 如果 `term < currentTerm`, 则返回 `false`
    2. 如果 `term > currentTerm`, 设置 `currentTerm = term`，重置 **Follower** 的 timeout
        > todo 是否可以继续执行？
        > info： 应该以 **Follower** 角色继续执行
    3. 重置 Follower 的 timeout
    4. 如果 **Follower** 的 `log[prevLogIndex].term != prevLogTerm`, 则返回 `false`
    5. 如果存在 `N`，使得 `log[entries[N].logIndex].term != entries[N].term`, 删除 `log[entries[N].logIndex]`（执行循环）
    6. 附加日志中尚未存在的任何新条目
    7. 如果 `leaderCommit > commitIndex` 则 `commitIndex = min(leaderCommit, min(entries[*].logIndex))`
    8. 如果 `commitIndex > lastApplied` ，那么就 `lastApplied` 加一，并把 `log[lastApplied]` 应用到状态机中（5.3 节）
    9. 返回 `currentTerm` 和 `true`
    
> todo, 如果是 **Leader**，会接受其他节点的请求吗？

- receive `requestVote`
    1. 如果 `term < currentTerm` 返回 false （5.2 节）
    2. 如果 `term > currentTerm`, 设置 `currentTerm = term`，重置 **Follower** 的 timeout，继续执行
    3. 重置 Follower 的 timeout
    4. 如果 `votedFor` 为**空**或者为 `candidateId`，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）
        > todo， 判断的是 commitIndex 和 lastLogIndex?
        > `(votedFor == null or votedFor == '' or votedFor == candidateId) and (lastLogIndex > log.last().logIndex)`
        > `(votedFor == null or votedFor == '' or votedFor == candidateId) and (lastLogIndex > commitIndex)`
> todo, 如果是 **Leader**，接收其他节点的投票请求吗？

- `timeout`
    1. 如果在超过选举超时时间的情况之前都没有收到领导人的心跳，或者是候选人请求投票的，就自己变成候选人
    
### Candidate

- 在转变成候选人后就立即开始选举过程
    - 自增当前的任期号（currentTerm）
    - 给自己投票
    - 重置选举超时计时器
    - 发送请求投票的 RPC 给其他所有服务器
- 如果接收到大多数服务器的选票，那么就变成领导人
- 如果接收到来自新的领导人的附加日志 RPC，转变成跟随者
- 如果选举过程超时，再次发起一轮选举
- 如果接收到的 RPC 请求或响应中，任期号 `T > currentTerm`，那么就令 `currentTerm` 等于 `T`，并切换状态为**跟随者**（5.1 节）
- 如果 `commitIndex > lastApplied`，那么就 `lastApplied` 加一，并把log[lastApplied]应用到状态机中（5.3 节）

- receive `heartbeat` or `appendEntries`
    1. 如果 `term < currentTerm`, 则返回 `false`
    2. 如果 `term >= currentTerm`, 设置 `currentTerm = term`，设置 role 为 **Follower**，并重置 **Follower** 的 timeout
        > info： 应该以 **Follower** 角色继续执行
        > 如果接收到来自新的领导人的附加日志 RPC，转变成跟随者

- receive `requestVote`
    1. 如果 `term < currentTerm` 返回 false （5.2 节）
    2. 如果 `term > currentTerm`, 设置 `currentTerm = term`，重置 **Follower** 的 timeout，继续执行
    3. 重置 Follower 的 timeout
    4. 如果 `votedFor` 为**空**或者为 `candidateId`，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）
        > todo， 判断的是 commitIndex 和 lastLogIndex?
        > `(votedFor == null or votedFor == '' or votedFor == candidateId) and (lastLogIndex > log.last().logIndex)`
        > `(votedFor == null or votedFor == '' or votedFor == candidateId) and (lastLogIndex > commitIndex)`

- send `requestVote`
    1. 发送选举前，执行如下：
        1. 自增当前的任期号（currentTerm）
        2. 给自己投票
        3. 重置选举超时计时器
        4. 发送请求投票的 RPC 给其他所有服务器
    2. 如果接收到的 RPC 响应中，任期号 `T > currentTerm`，那么就令 `currentTerm` 等于 `T`，并切换状态为**跟随者**（5.1 节）
        > loop 选出最大的 `T`，然后退出选举流程
    3. 如果接收到大多数服务器的选票，那么就变成领导人
    4. 如果接收到来自新的领导人的附加日志 RPC，转变成跟随者
    5. 如果选举过程超时，再次发起一轮选举

### Leader

- receive `heartbeat` or `appendEntries`
    1. 如果 `term <= currentTerm`, 则返回 `false`
    2. 如果 `term > currentTerm`, 设置 `currentTerm = term`，重置 **Follower** 的 timeout
        > info： 应该以 **Follower** 角色继续执行
   
- receive `requestVote`:
    1. 如果 `term <= currentTerm` 返回 false （5.2 节）
    2. 如果 `term > currentTerm`, 设置 `currentTerm = term`，重置 **Follower** 的 timeout，继续执行
        > info： 应该以 **Follower** 角色继续执行



## api

### 

- Follower 相关的超时时间应该要比 Candidate 超时时间长
    - 否则 Follower 刚给其他 Candidate 投完票，但是由于 Follower 超时而变成了 Candidate
    - 这样就会导致多个节点发起多次选举，而使 Leader 选举低效。

- Leader 在收到 投票请求 时，如果投票请求的 term 参数大于 Leader 的 currentTerm，Leader 必须立刻变成 Follower，并立刻返回？

### 问题

如果有一个节点由于网络问题，一直不能与集群中的其他节点互联，那么其自身就会不断的竞选选举，将自身的 currentTerm 加一。
突然这个节点的网络问题修复了，由于其任期比集群中的节点都要大，就会当选 Leader，但是 raft 的 Leader 不会接受其他节点的 Log，
从而导致其他 Follower 已提交的 Log 被 Leader 覆写。

> 2. 如果 votedFor 为空或者为 candidateId，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）

> **安全性**：在 Raft 中安全性的关键是在图 3 中展示的状态机安全：
> 如果有任何的服务器节点已经应用了一个确定的日志条目到它的状态机中，
> 那么其他服务器节点不能在同一个日志索引位置应用一个不同的指令。
> 章节 5.4 阐述了 Raft 算法是如何保证这个特性的；
> 这个解决方案涉及到一个额外的选举机制（5.2 节）上的限制。

### 

### 

## ref

- [raft-zh_cn](https://github.com/maemual/raft-zh_cn/blob/master/raft-zh_cn.md)
- https://raft.github.io/raft.pdf
- [Raft实现指南](https://zhuanlan.zhihu.com/p/26506491)
- [Raft-实现指北-领导选举](https://www.hashcoding.net/2018/01/07/Raft-%E5%AE%9E%E7%8E%B0%E6%8C%87%E5%8C%97-%E9%A2%86%E5%AF%BC%E9%80%89%E4%B8%BE/)
- [raft算法与paxos算法相比有什么优势，使用场景有什么差异？ - 朱一聪的回答 - 知乎](https://www.zhihu.com/question/36648084/answer/82332860)
- [Raft 实现指北-开篇](https://www.hashcoding.net/2018/01/01/Raft-%E5%AE%9E%E7%8E%B0%E6%8C%87%E5%8C%97-%E5%BC%80%E7%AF%87/) 