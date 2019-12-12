package io.github.qyvlik.rafttoy.node;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

class RaftLeaderElectionTest {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Test
    void exec() {
        long period = 100L;
        int nodeSize = 3;
        Set<String> nodeIds = new HashSet<>();
        while (nodeSize-- > 0) {
            nodeIds.add("node-" + nodeSize);
        }

        Executor executor = Executors.newFixedThreadPool(nodeIds.size());

        Map<String, RaftNodeRunner> runnerMap = new ConcurrentHashMap<>();

        for (String nodeId : nodeIds) {
            createRunnerAndExec(nodeId, nodeIds, runnerMap, executor);
        }

        startHeartbeat(runnerMap, 1000L + (long) (period * 0.3), period);

        startFollowerTimeout(runnerMap, 1000, period);

        startCandidateTimeout(runnerMap, 1000L + (long) (period * 0.6), period);

        while (true) {
            sleep(1);
        }
    }

    private void startFollowerTimeout(Map<String, RaftNodeRunner> runnerMap,
                                      long initialDelay,
                                      long period) {
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        for (RaftNodeRunner raftNodeRunner : runnerMap.values()) {
                            raftNodeRunner.send(RaftCommand.followerTimeout());
                        }
                    }
                }, initialDelay, period, TimeUnit.MILLISECONDS);
    }

    private void startCandidateTimeout(Map<String, RaftNodeRunner> runnerMap,
                                       long initialDelay,
                                       long period) {
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        for (RaftNodeRunner raftNodeRunner : runnerMap.values()) {
                            raftNodeRunner.send(RaftCommand.candidateTimeout());
                        }
                    }
                }, initialDelay, period, TimeUnit.MILLISECONDS);
    }

    private void startHeartbeat(Map<String, RaftNodeRunner> runnerMap,
                                long initialDelay,
                                long period) {
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleAtFixedRate(
                new Runnable() {
                    @Override
                    public void run() {
                        for (RaftNodeRunner raftNodeRunner : runnerMap.values()) {
                            raftNodeRunner.send(RaftCommand.heartbeat());
                        }
                    }
                }, initialDelay, period, TimeUnit.MILLISECONDS);
    }


    private void createRunnerAndExec(String nodeId,
                                     Set<String> nodeIds,
                                     Map<String, RaftNodeRunner> runnerMap,
                                     Executor executor) {
        RaftRpcSender sender = new RaftRpcSender() {
            @Override
            public Future<AppendEntriesResult> appendEntries(String node, AppendEntriesParams params) {
                ResultFuture<AppendEntriesResult> future = new ResultFuture<AppendEntriesResult>();
                RaftCommand raftCommand = new RaftCommand();
                raftCommand.setParams(params);
                raftCommand.setType(RaftCommand.Type.appendEntries);
                raftCommand.setCallback(new RaftCommand.Callback() {
                    @Override
                    public void ret(Object result) {
                        future.setResult((AppendEntriesResult) result);
                    }
                });
                RaftNodeRunner raftNodeRunner = runnerMap.get(node);
                // logger.info("sender:{} to {}, command:{}", nodeId, node, raftCommand);

                sleep((long) (100 * Math.random()));            // mock network delay

                raftNodeRunner.send(raftCommand);
                return future;
            }

            @Override
            public Future<RequestVoteResult> requestVote(String node, RequestVoteParams params) {
                ResultFuture<RequestVoteResult> future = new ResultFuture<RequestVoteResult>();
                RaftCommand raftCommand = new RaftCommand();
                raftCommand.setParams(params);
                raftCommand.setType(RaftCommand.Type.requestVote);
                raftCommand.setCallback(new RaftCommand.Callback() {
                    @Override
                    public void ret(Object result) {
                        RequestVoteResult requestVoteResult = (RequestVoteResult) result;
//                        if (requestVoteResult.getVoteGranted()) {
//                            logger.info("requestVote from:{}, to:{}, voteGranted:{}, term:{}",
//                                    nodeId, node, requestVoteResult.getVoteGranted(), params.getTerm());
//                        }
                        future.setResult(requestVoteResult);
                    }
                });
                RaftNodeRunner raftNodeRunner = runnerMap.get(node);
                // logger.info("sender:{} to {}, command:{}", nodeId, node, raftCommand);

                sleep((long) (100 * Math.random()));        // mock network delay

                raftNodeRunner.send(raftCommand);
                return future;
            }
        };
        RaftNode raftNode = new RaftNode(nodeId, nodeIds, sender);
        RaftNodeRunner raftNodeRunner = new RaftNodeRunner(new LinkedBlockingQueue<>(), raftNode);
        runnerMap.put(nodeId, raftNodeRunner);

        executor.execute(raftNodeRunner);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {

        }
    }
}