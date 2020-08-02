package org.dromara.hodor.server.service;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.dromara.hodor.common.exception.HodorException;
import org.dromara.hodor.common.utils.CopySets;
import org.dromara.hodor.common.utils.ThreadUtils;
import org.dromara.hodor.core.entity.CopySet;
import org.dromara.hodor.core.entity.HodorMetadata;
import org.dromara.hodor.core.JobInfo;
import org.dromara.hodor.core.manager.CopySetManager;
import org.dromara.hodor.core.manager.NodeServerManager;
import org.dromara.hodor.core.service.JobInfoService;
import org.dromara.hodor.scheduler.api.HodorScheduler;
import org.dromara.hodor.scheduler.api.SchedulerManager;
import org.dromara.hodor.scheduler.api.config.SchedulerConfig;
import org.dromara.hodor.server.component.Constants;
import org.dromara.hodor.server.component.LifecycleComponent;
import org.dromara.hodor.server.listener.LeaderElectChangeListener;
import org.dromara.hodor.server.listener.MetadataChangeListener;
import org.dromara.hodor.server.listener.ServerNodeChangeListener;
import org.springframework.stereotype.Service;

/**
 * hodor service
 *
 * @author tomgs
 * @since 2020/6/29
 */
@Slf4j
@Service
public class HodorService implements LifecycleComponent {

    private final LeaderService leaderService;

    private final RegisterService registerService;

    private final JobInfoService jobInfoService;

    private final NodeServerManager nodeServerManager;

    private final CopySetManager copySetManager;

    private final SchedulerManager schedulerManager;

    public HodorService(final LeaderService leaderService, final RegisterService registerService, final JobInfoService jobInfoService) {
        this.leaderService = leaderService;
        this.registerService = registerService;
        this.jobInfoService = jobInfoService;
        this.nodeServerManager = NodeServerManager.getInstance();
        this.copySetManager = CopySetManager.getInstance();
        this.schedulerManager = SchedulerManager.getInstance();
    }

    @Override
    public void start() {
        Integer currRunningNodeCount = registerService.getRunningNodeCount();
        while (currRunningNodeCount < Constants.LEAST_NODE_COUNT) {
            ThreadUtils.sleep(TimeUnit.MILLISECONDS, 1000);
            currRunningNodeCount = registerService.getRunningNodeCount();
        }
        //init data
        registerService.registryMetadataListener(new MetadataChangeListener(this));
        registerService.registryElectLeaderListener(new LeaderElectChangeListener(this));
        //select leader
        electLeader();
    }

    @Override
    public void stop() {
        registerService.stop();
        copySetManager.clearCopySet();
        nodeServerManager.clearNodeServer();
    }

    public void electLeader() {
        leaderService.electLeader(() -> {
            log.info("to be leader.");
            registerService.registryServerNodeListener(new ServerNodeChangeListener(nodeServerManager));
            // after to be leader write here
            List<String> currRunningNodes = registerService.getRunningNodes();
            if (CollectionUtils.isEmpty(currRunningNodes)) {
                throw new HodorException("running node count is 0.");
            }

            List<List<String>> copySetNodes = CopySets.buildCopySets(currRunningNodes, Constants.REPLICA_COUNT, Constants.SCATTER_WIDTH);
            int setsNum = Math.max(copySetNodes.size(), currRunningNodes.size());
            // distribution copySet
            List<CopySet> copySets = Lists.newArrayList();
            for (int i = 0; i < setsNum; i++) {
                int setsIndex = setsNum % copySetNodes.size();
                List<String> copySetNode = copySetNodes.get(setsIndex);
                CopySet copySet = new CopySet();
                copySet.setId(setsIndex);
                copySet.setServers(copySetNode);
                copySets.add(copySet);
            }

            // get metadata and update
            int jobCount = jobInfoService.queryAssignableJobCount();
            int offset = (int) Math.ceil((double) jobCount / setsNum);
            List<Integer> interval = Lists.newArrayList();
            for (int i = 0; i < setsNum; i++) {
                Integer id = jobInfoService.queryJobIdByOffset(offset * i);
                interval.add(id);
            }
            for (int i = 0; i < interval.size(); i++) {
                CopySet copySet = copySets.get(i);
                if (i == interval.size() - 1) {
                    copySet.setDataInterval(Lists.newArrayList(interval.get(i)));
                } else {
                    copySet.setDataInterval(Lists.newArrayList(interval.get(i), interval.get(i + 1)));
                }
                copySet.setLeader(copySetManager.selectLeaderCopySet(copySet));
            }

            final HodorMetadata metadata = HodorMetadata.builder()
                .nodes(currRunningNodes)
                .interval(interval)
                .copySets(copySets)
                .build();
            registerService.createMetadata(metadata);
        });
    }

    public void createActiveScheduler(String serverId, List<Integer> dataInterval) {
        HodorScheduler activeScheduler = getScheduler(serverId, dataInterval);
        schedulerManager.addActiveScheduler(activeScheduler);
        schedulerManager.addSchedulerDataInterval(activeScheduler.getSchedulerName(), dataInterval);
    }

    public void createStandbyScheduler(String serverId, List<Integer> standbyDataInterval) {
        HodorScheduler standbyScheduler = getScheduler(serverId, standbyDataInterval);
        schedulerManager.addStandByScheduler(standbyScheduler);
        schedulerManager.addSchedulerDataInterval(standbyScheduler.getSchedulerName(), standbyDataInterval);
    }

    public HodorScheduler getScheduler(String serverId, List<Integer> dataInterval) {
        SchedulerConfig config = SchedulerConfig.builder().schedulerName("HodorScheduler_" + serverId).threadCount(8).misfireThreshold(3000).build();
        HodorScheduler scheduler = schedulerManager.getScheduler(config.getSchedulerName());
        if (scheduler == null) {
            scheduler = schedulerManager.createScheduler(config);
        }
        List<Integer> schedulerDataInterval = schedulerManager.getSchedulerDataInterval(config.getSchedulerName());
        if (CollectionUtils.isEmpty(schedulerDataInterval) || !CollectionUtils.isEqualCollection(schedulerDataInterval, dataInterval)) {
            List<JobInfo> jobInfoList = jobInfoService.queryJobInfoByOffset(dataInterval.get(0), dataInterval.get(1));
            scheduler.addJobList(jobInfoList);
        }
        return scheduler;
    }

}
