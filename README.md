# raft-toy

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