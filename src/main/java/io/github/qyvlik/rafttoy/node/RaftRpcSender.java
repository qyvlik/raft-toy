package io.github.qyvlik.rafttoy.node;

import java.util.concurrent.Future;

public interface RaftRpcSender {

    /**
     * 附加日志
     *
     * @param params 附加日志 参数
     * @return 附加日志 结果
     */
    Future<AppendEntriesResult> appendEntries(String node, AppendEntriesParams params);

    /**
     * @param params 请求投票 参数
     * @return 请求投票 结果
     */
    Future<RequestVoteResult> requestVote(String node, RequestVoteParams params);
}
