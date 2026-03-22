/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.knowledge.enums.ProcessMode;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.core.parser.ParserType;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.knowledge.schedule.CronScheduleHelper;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.knowledge.mq.KnowledgeDocumentChunkProducer;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final DocumentParserSelector parserSelector;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final FileStorageService fileStorageService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final EmbeddingService embeddingService;
    private final HttpClientHelper httpClientHelper;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentScheduleService scheduleService;
    private final IngestionPipelineService ingestionPipelineService;
    private final IngestionPipelineMapper ingestionPipelineMapper;
    private final IngestionEngine ingestionEngine;
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;
    private final PlatformTransactionManager transactionManager;
    private final KnowledgeDocumentChunkProducer chunkProducer;


    @Value("${kb.chunk.semantic.targetChars:1400}")
    private int targetChars;
    @Value("${kb.chunk.semantic.maxChars:1800}")
    private int maxChars;
    @Value("${kb.chunk.semantic.minChars:600}")
    private int minChars;
    @Value("${kb.chunk.semantic.overlapChars:0}")
    private int overlapChars;
    @Value("${rag.knowledge.schedule.min-interval-seconds:60}")
    private long scheduleMinIntervalSeconds;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest request, MultipartFile file) {
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        SourceType sourceType = normalizeSourceType(request == null ? null : request.getSourceType(), file);
        String sourceLocation = request == null ? null : request.getSourceLocation();
        if (StringUtils.hasText(sourceLocation)) {
            sourceLocation = sourceLocation.trim();
        }
        boolean scheduleEnabled = request != null && Boolean.TRUE.equals(request.getScheduleEnabled());
        if (SourceType.FILE == sourceType) {
            scheduleEnabled = false;
        }
        String scheduleCron = request == null ? null : request.getScheduleCron();
        if (StringUtils.hasText(scheduleCron)) {
            scheduleCron = scheduleCron.trim();
        }

        if (SourceType.URL == sourceType && !StringUtils.hasText(sourceLocation)) {
            throw new ClientException("来源地址不能为空");
        }
        if (scheduleEnabled && !StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        if (scheduleEnabled) {
            try {
                if (CronScheduleHelper.isIntervalLessThan(scheduleCron, new java.util.Date(), scheduleMinIntervalSeconds)) {
                    throw new ClientException("定时周期不能小于 " + scheduleMinIntervalSeconds + " 秒");
                }
            } catch (IllegalArgumentException e) {
                throw new ClientException("定时表达式不合法");
            }
        }

        StoredFileDTO stored = resolveStoredFile(kbDO.getCollectionName(), sourceType, sourceLocation, file);

        ProcessMode processMode = ProcessMode.normalize(request == null ? null : request.getProcessMode());
        ChunkingMode chunkingMode = null;
        String chunkConfig = null;
        String pipelineId = null;

        if (ProcessMode.CHUNK == processMode) {
            // 分块模式：解析分块策略和配置
            chunkingMode = resolveChunkingMode(request == null ? null : request.getChunkStrategy());
            chunkConfig = buildChunkConfigJson(chunkingMode, request);
        } else if (ProcessMode.PIPELINE == processMode) {
            // Pipeline模式：验证Pipeline ID
            if (request == null || !StringUtils.hasText(request.getPipelineId())) {
                throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
            }
            pipelineId = request.getPipelineId();
            // 验证Pipeline是否存在
            try {
                ingestionPipelineService.get(request.getPipelineId());
            } catch (Exception e) {
                throw new ClientException("指定的Pipeline不存在: " + request.getPipelineId());
            }
        }

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(kbId)
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? sourceLocation : null)
                .scheduleEnabled(scheduleEnabled ? 1 : 0)
                .scheduleCron(scheduleEnabled ? scheduleCron : null)
                .processMode(processMode.getValue())
                .chunkStrategy(chunkingMode != null ? chunkingMode.getValue() : null)
                .chunkConfig(chunkConfig)
                .pipelineId(pipelineId)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        docMapper.insert(documentDO);

        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startChunk(String docId) {
        // 原子 CAS 更新：WHERE status != 'running'，并发请求只有一个能成功
        int updated = docMapper.update(
                new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername())
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
        if (updated == 0) {
            // 文档不存在或已在分块中，查一下给出准确提示
            KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
            Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
            throw new ClientException("文档分块操作正在进行中，请稍后再试");
        }

        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        scheduleService.upsertSchedule(documentDO);
        // 发送 MQ 消息，由消费者异步执行耗时的分块任务
        chunkProducer.sendChunkEvent(KnowledgeDocumentChunkEvent.builder()
                .docId(docId)
                .kbId(documentDO.getKbId())
                .operator(UserContext.getUsername())
                .build());
    }

    @Override
    public void executeChunk(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        if (documentDO == null) {
            log.warn("文档不存在，跳过分块任务, docId={}", docId);
            return;
        }

        // 统一在执行前清理旧向量，非 pipeline 模式后续会写入新向量，pipeline 模式由管道自身 indexer 写入
        vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
        runChunkTask(documentDO);
    }

    private void runChunkTask(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        ProcessMode processMode = ProcessMode.normalize(documentDO.getProcessMode());

        KnowledgeDocumentChunkLogDO chunkLog = KnowledgeDocumentChunkLogDO.builder()
                .docId(documentDO.getId())
                .status(DocumentStatus.RUNNING.getCode())
                .processMode(processMode.getValue())
                .chunkStrategy(documentDO.getChunkStrategy())
                .pipelineId(documentDO.getPipelineId())
                .startTime(new Date())
                .build();
        chunkLogMapper.insert(chunkLog);

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embeddingDuration = 0;

        try {
            List<VectorChunk> chunkResults;
            if (ProcessMode.PIPELINE == processMode) {
                long start = System.currentTimeMillis();
                chunkResults = runPipelineProcess(documentDO);
                chunkDuration = System.currentTimeMillis() - start;
            } else {
                ChunkProcessResult result = runChunkProcess(documentDO);
                extractDuration = result.extractDuration();
                chunkDuration = result.chunkDuration();
                chunkResults = result.chunks();
            }

            // 删旧写新同一事务保证 DB 原子性；向量写入后再更新状态 SUCCESS
            int savedCount = persistChunks(docId, chunkResults);

            if (ProcessMode.PIPELINE != processMode) {
                long embeddingStart = System.currentTimeMillis();
                vectorStoreService.indexDocumentChunks(String.valueOf(documentDO.getKbId()), docId, chunkResults);
                embeddingDuration = System.currentTimeMillis() - embeddingStart;
            }

            markChunkSuccess(documentDO.getId(), savedCount);

            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.SUCCESS.getCode(), savedCount,
                    extractDuration, chunkDuration, embeddingDuration, totalDuration, null);

        } catch (Exception e) {
            log.error("文档分块任务执行失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                    extractDuration, chunkDuration, embeddingDuration, totalDuration, e.getMessage());
        }
    }

    /**
     * 将分块结果持久化到 DB（删旧写新原子执行），返回实际保存的分块数
     */
    private int persistChunks(String docId, List<VectorChunk> chunkResults) {
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    return req;
                })
                .toList();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            knowledgeChunkService.deleteByDocId(docId);
            knowledgeChunkService.batchCreate(docId, chunks);
        });
        return chunks.size();
    }

    private void markChunkSuccess(String docId, int chunkCount) {
        KnowledgeDocumentDO update = new KnowledgeDocumentDO();
        update.setId(docId);
        update.setChunkCount(chunkCount);
        update.setStatus(DocumentStatus.SUCCESS.getCode());
        update.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(update);
    }

    private void updateChunkLog(String logId, String status, int chunkCount, long extractDuration,
                                long chunkDuration, long embeddingDuration, long totalDuration,
                                String errorMessage) {
        KnowledgeDocumentChunkLogDO update = new KnowledgeDocumentChunkLogDO();
        update.setId(logId);
        update.setStatus(status);
        update.setChunkCount(chunkCount);
        update.setExtractDuration(extractDuration);
        update.setChunkDuration(chunkDuration);
        update.setEmbeddingDuration(embeddingDuration);
        update.setTotalDuration(totalDuration);
        update.setErrorMessage(errorMessage);
        update.setEndTime(new Date());
        chunkLogMapper.updateById(update);
    }

    /**
     * 使用分块策略处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     */
    private ChunkProcessResult runChunkProcess(KnowledgeDocumentDO documentDO) {
        ChunkingMode chunkingMode = resolveChunkingMode(documentDO.getChunkStrategy());
        String embeddingModel = resolveEmbeddingModel(documentDO.getKbId());
        ChunkingOptions config = buildChunkingOptions(chunkingMode, documentDO, embeddingModel);

        long extractStart = System.currentTimeMillis();
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            String text = parserSelector.select(ParserType.TIKA.getType()).extractText(is, documentDO.getDocName());
            long extractDuration = System.currentTimeMillis() - extractStart;

            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(chunkingMode);
            long chunkStart = System.currentTimeMillis();
            List<VectorChunk> chunks = chunkingStrategy.chunk(text, config);
            long chunkDuration = System.currentTimeMillis() - chunkStart;

            return new ChunkProcessResult(chunks, extractDuration, chunkDuration);
        } catch (Exception e) {
            throw new RuntimeException("文档内容提取或分块失败", e);
        }
    }

    private record ChunkProcessResult(List<VectorChunk> chunks, long extractDuration, long chunkDuration) {
    }

    /**
     * 使用 Pipeline 处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     */
    private List<VectorChunk> runPipelineProcess(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        String pipelineId = documentDO.getPipelineId();

        if (pipelineId == null) {
            throw new IllegalStateException("Pipeline模式下Pipeline ID为空：docId=" + docId);
        }

        KnowledgeBaseDO kbDO = kbMapper.selectById(documentDO.getKbId());
        if (kbDO == null) {
            throw new IllegalStateException("知识库不存在：kbId=" + documentDO.getKbId());
        }

        PipelineDefinition pipelineDef = ingestionPipelineService.getDefinition(pipelineId);

        byte[] fileBytes;
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            fileBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件内容失败：docId=" + docId, e);
        }

        IngestionContext context = IngestionContext.builder()
                .taskId(docId)
                .pipelineId(pipelineId)
                .rawBytes(fileBytes)
                .mimeType(documentDO.getFileType())
                .vectorSpaceId(VectorSpaceId.builder()
                        .logicalName(kbDO.getCollectionName())
                        .build())
                .build();

        IngestionContext result = ingestionEngine.execute(pipelineDef, context);

        if (result.getError() != null) {
            throw new RuntimeException("Pipeline执行失败：" + result.getError().getMessage(), result.getError());
        }

        List<VectorChunk> chunks = result.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            log.warn("Pipeline执行完成但未产生分块：docId={}", docId);
            return List.of();
        }

        return chunks;
    }

    public void chunkDocument(KnowledgeDocumentDO documentDO) {
        if (documentDO == null) {
            return;
        }
        runChunkTask(documentDO);
    }

    private void markChunkFailed(String docId) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        txTemplate.executeWithoutResult(status -> {
            KnowledgeDocumentDO update = new KnowledgeDocumentDO();
            update.setId(docId);
            update.setStatus(DocumentStatus.FAILED.getCode());
            update.setUpdatedBy(UserContext.getUsername());
            docMapper.updateById(update);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        docMapper.deleteById(documentDO);

        vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
    }

    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        KnowledgeDocumentDO update = new KnowledgeDocumentDO();
        update.setId(documentDO.getId());
        update.setDocName(docName.trim());
        update.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(update);
    }

    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, Page<KnowledgeDocumentVO> page, String status, String keyword) {
        Page<KnowledgeDocumentDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(keyword != null && !keyword.isBlank(), KnowledgeDocumentDO::getDocName, keyword)
                .eq(status != null && !status.isBlank(), KnowledgeDocumentDO::getStatus, status)
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);

        IPage<KnowledgeDocumentDO> result = docMapper.selectPage(mpPage, qw);

        Page<KnowledgeDocumentVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class)).toList());
        return voPage;
    }

    @Override
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        int size = Math.min(Math.max(limit, 1), 20);
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = docMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        Set<String> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = kbMapper.selectByIds(kbIds);
        Map<String, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enable(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = docMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        documentDO.setEnabled(enabled ? 1 : 0);
        documentDO.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(documentDO);
        scheduleService.syncScheduleIfExists(documentDO);

        // 同步更新 Chunk 表的状态
        knowledgeChunkService.updateEnabledByDocId(docId, enabled);

        if (!enabled) {
            // 禁用文档时，从向量库中删除对应的向量
            vectorStoreService.deleteDocumentVectors(String.valueOf(documentDO.getKbId()), docId);
        } else {
            // 启用文档时，根据文档分块记录重建向量索引
            String embeddingModel = resolveEmbeddingModel(documentDO.getKbId());
            List<KnowledgeChunkVO> chunks = knowledgeChunkService.listByDocId(docId);
            List<VectorChunk> vectorChunks = chunks.parallelStream().map(each -> {
                        List<Float> embed = embedContent(each.getContent(), embeddingModel);
                        return VectorChunk.builder()
                                .chunkId(each.getId())
                                .content(each.getContent())
                                .index(each.getChunkIndex())
                                .embedding(toArray(embed))
                                .build();
                    })
                    .toList();
            if (CollUtil.isNotEmpty(vectorChunks)) {
                vectorStoreService.indexDocumentChunks(String.valueOf(documentDO.getKbId()), docId, vectorChunks);
            }
        }
    }

    @Override
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        Page<KnowledgeDocumentChunkLogDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentChunkLogDO> qw = new LambdaQueryWrapper<KnowledgeDocumentChunkLogDO>()
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                .orderByDesc(KnowledgeDocumentChunkLogDO::getCreateTime);

        IPage<KnowledgeDocumentChunkLogDO> result = chunkLogMapper.selectPage(mpPage, qw);

        List<KnowledgeDocumentChunkLogDO> records = result.getRecords();
        Map<String, String> pipelineNameMap = new HashMap<>();
        if (CollUtil.isNotEmpty(records)) {
            Set<String> pipelineIds = new HashSet<>();
            for (KnowledgeDocumentChunkLogDO record : records) {
                if (record.getPipelineId() != null) {
                    pipelineIds.add(record.getPipelineId());
                }
            }
            if (!pipelineIds.isEmpty()) {
                List<IngestionPipelineDO> pipelines = ingestionPipelineMapper.selectByIds(pipelineIds);
                if (CollUtil.isNotEmpty(pipelines)) {
                    for (IngestionPipelineDO pipeline : pipelines) {
                        pipelineNameMap.put(pipeline.getId(), pipeline.getName());
                    }
                }
            }
        }

        Page<KnowledgeDocumentChunkLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream().map(each -> {
            KnowledgeDocumentChunkLogVO vo = BeanUtil.toBean(each, KnowledgeDocumentChunkLogVO.class);
            if (each.getPipelineId() != null) {
                vo.setPipelineName(pipelineNameMap.get(each.getPipelineId()));
            }
            Long totalDuration = each.getTotalDuration();
            if (totalDuration != null) {
                long other = getOther(each, totalDuration);
                vo.setOtherDuration(Math.max(0, other));
            }
            return vo;
        }).toList());
        return voPage;
    }

    private static long getOther(KnowledgeDocumentChunkLogDO each, Long totalDuration) {
        String mode = each.getProcessMode();
        boolean pipelineMode = ProcessMode.PIPELINE.getValue().equalsIgnoreCase(mode);
        long extract = each.getExtractDuration() == null ? 0 : each.getExtractDuration();
        long chunk = each.getChunkDuration() == null ? 0 : each.getChunkDuration();
        long embedding = each.getEmbeddingDuration() == null ? 0 : each.getEmbeddingDuration();
        return pipelineMode
                ? totalDuration - extract - chunk
                : totalDuration - extract - chunk - embedding;
    }

    private String resolveEmbeddingModel(String kbId) {
        if (kbId == null) {
            return null;
        }
        KnowledgeBaseDO kbDO = kbMapper.selectById(kbId);
        return kbDO != null ? kbDO.getEmbeddingModel() : null;
    }

    private List<Float> embedContent(String content, String embeddingModel) {
        if (!StringUtils.hasText(embeddingModel)) {
            return embeddingService.embed(content);
        }
        return embeddingService.embed(content, embeddingModel);
    }

    private void patchStatus(KnowledgeDocumentDO doc) {
        doc.setStatus(DocumentStatus.RUNNING.getCode());
        doc.setUpdatedBy(UserContext.getUsername());
        docMapper.updateById(doc);
    }

    private SourceType normalizeSourceType(String sourceType, MultipartFile file) {
        if (!StringUtils.hasText(sourceType)) {
            return file == null ? SourceType.URL : SourceType.FILE;
        }
        SourceType result = SourceType.fromValue(sourceType);
        if (result == null) {
            throw new ClientException("不支持的来源类型: " + sourceType);
        }
        return result;
    }

    private StoredFileDTO resolveStoredFile(String bucketName, SourceType sourceType, String sourceLocation, MultipartFile file) {
        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
            return fileStorageService.upload(bucketName, file);
        }

        HttpClientHelper.HttpFetchResponse response = httpClientHelper.get(sourceLocation, Map.of());
        String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : "remote-file";
        return fileStorageService.upload(bucketName, response.body(), fileName, response.contentType());
    }

    private ChunkingMode resolveChunkingMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return ChunkingMode.STRUCTURE_AWARE;
        }
        return ChunkingMode.fromValue(mode);
    }

    private ChunkingOptions buildChunkingOptions(ChunkingMode mode, KnowledgeDocumentDO documentDO, String embeddingModel) {
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        Map<String, Object> config = parseChunkConfig(documentDO.getChunkConfig());
        if (mode == ChunkingMode.FIXED_SIZE) {
            Integer chunkSize = getConfigInt(config, "chunkSize", 512);
            Integer overlapSize = getConfigInt(config, "overlapSize", 128);
            Map<String, Object> metadata = new HashMap<>();
            if (StringUtils.hasText(embeddingModel)) {
                metadata.put("embeddingModel", embeddingModel);
            }
            return ChunkingOptions.builder()
                    .chunkSize(chunkSize)
                    .overlapSize(overlapSize)
                    .metadata(metadata)
                    .build();
        }
        Integer target = getConfigInt(config, "targetChars", targetChars);
        Integer max = getConfigInt(config, "maxChars", maxChars);
        Integer min = getConfigInt(config, "minChars", minChars);
        Integer overlap = getConfigInt(config, "overlapChars", overlapChars);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("targetChars", target);
        metadata.put("maxChars", max);
        metadata.put("minChars", min);
        metadata.put("overlapChars", overlap);
        if (StringUtils.hasText(embeddingModel)) {
            metadata.put("embeddingModel", embeddingModel);
        }

        return ChunkingOptions.builder()
                .chunkSize(target)
                .overlapSize(overlap)
                .metadata(metadata)
                .build();
    }

    private String buildChunkConfigJson(ChunkingMode mode, KnowledgeDocumentUploadRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.hasText(request.getChunkConfig())) {
            return request.getChunkConfig().trim();
        }
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        Map<String, Object> params = new HashMap<>();
        if (mode == ChunkingMode.FIXED_SIZE) {
            if (request.getChunkSize() != null) {
                params.put("chunkSize", request.getChunkSize());
            }
            if (request.getOverlapSize() != null) {
                params.put("overlapSize", request.getOverlapSize());
            }
        } else {
            if (request.getTargetChars() != null) {
                params.put("targetChars", request.getTargetChars());
            }
            if (request.getMaxChars() != null) {
                params.put("maxChars", request.getMaxChars());
            }
            if (request.getMinChars() != null) {
                params.put("minChars", request.getMinChars());
            }
            if (request.getOverlapChars() != null) {
                params.put("overlapChars", request.getOverlapChars());
            }
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new ServiceException("分块参数序列化失败");
        }
    }

    private Map<String, Object> parseChunkConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("分块参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    private Integer getConfigInt(Map<String, Object> config, String key, Integer defaultValue) {
        if (config == null || config.isEmpty()) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
