package com.libre.video.service.impl;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.PageDTO;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.collect.Lists;
import com.libre.core.exception.LibreException;
import com.libre.core.toolkit.CollectionUtil;
import com.libre.core.toolkit.StringUtil;
import com.libre.oss.support.OssTemplate;
import com.libre.video.config.VideoProperties;
import com.libre.video.constant.SystemConstants;
import com.libre.video.core.download.M3u8Download;
import com.libre.video.core.download.VideoEncode;
import com.libre.video.core.enums.RequestTypeEnum;
import com.libre.video.core.event.VideoUploadEvent;
import com.libre.video.core.request.VideoRequestContext;
import com.libre.video.core.request.strategy.VideoRequestStrategy;
import com.libre.video.mapper.VideoEsRepository;
import com.libre.video.mapper.VideoMapper;
import com.libre.video.pojo.Video;
import com.libre.video.core.pojo.dto.VideoRequestParam;
import com.libre.video.pojo.dto.VideoQuery;
import com.libre.video.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.*;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.core.query.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video> implements VideoService {

	private final VideoRequestContext videoRequestContext;

	private final VideoEncode videoEncode;

	private final VideoEsRepository videoEsRepository;

	private final ElasticsearchOperations elasticsearchOperations;

	private final OssTemplate ossTemplate;

	private final M3u8Download m3u8Download;

	private final VideoProperties properties;

	@Override
	public void request(VideoRequestParam param) {
		RequestTypeEnum requestTypeEnum = RequestTypeEnum.find(param.getRequestType());
		Assert.notNull(requestTypeEnum, "request type must not be null");
		log.info("start request type: {}, baseUrl: {}", requestTypeEnum.name(), requestTypeEnum.getBaseUrl());
		param.setRequestTypeEnum(requestTypeEnum);
		VideoRequestStrategy requestStrategy = videoRequestContext.getRequestStrategy(param.getRequestType());
		requestStrategy.execute(param);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void download(List<Long> ids) {
		for (Long id : ids) {
			videoEncode.encodeAndWrite(id);
		}
	}

	@Override
	public void saveVideoToOss(VideoUploadEvent event) {
		Video video = event.getVideo();
		String videoPath = video.getVideoPath();
		Resource resource = event.getResource();

		Assert.hasText(videoPath, "video path must not be null");
		Assert.notNull(resource, "video resource must not be null");

		try (InputStream inputStream = resource.getInputStream()) {
			ossTemplate.putObject(SystemConstants.VIDEO_BUCKET_NAME, videoPath, inputStream);
		}
		catch (IOException e) {
			throw new LibreException("文件上传失败: " + e.getMessage());
		}
		log.info("video save success, url: {}", video.getVideoPath());
		this.updateById(video);
	}

	@Override
	@SuppressWarnings("unchecked")
	public Page<Video> findByPage(PageDTO<Video> page, VideoQuery videoQuery) {
		PageRequest pageRequest = PageRequest.of((int) page.getCurrent(), (int) page.getSize());


		NativeSearchQueryBuilder nativeSearchQueryBuilder = new NativeSearchQueryBuilder();

		String title = videoQuery.getTitle();
		if (StringUtil.isNotBlank(title)) {
			nativeSearchQueryBuilder.withQuery(QueryBuilders.matchPhraseQuery("title", title));
			nativeSearchQueryBuilder.withQuery(QueryBuilders.matchQuery("title", title));
		}
		nativeSearchQueryBuilder.withPageable(pageRequest);
		nativeSearchQueryBuilder.withSorts(sortBuilders(page));
		NativeSearchQuery query = nativeSearchQueryBuilder.build();
		SearchHits<Video> hits = elasticsearchOperations.search(query, Video.class);
		SearchPage<Video> searchPage = SearchHitSupport.searchPageFor(hits, query.getPageable());
		return (Page<Video>) SearchHitSupport.unwrapSearchHits(searchPage);
	}

	@Override
	public void watch(Long videoId) {
		Video video = Optional.ofNullable(this.getById(videoId))
				.orElseThrow(() -> new LibreException(String.format("video not exist, videoId: %d", videoId)));
		S3Object object;
		try {
			object = ossTemplate.getObject(SystemConstants.VIDEO_BUCKET_NAME, video.getVideoPath());
		}
		catch (Exception e) {
			throw new LibreException(String.format("video not exist, videoId: %d", videoId));
		}
		log.info("file name is: {}", object.getKey());
		S3ObjectInputStream inputStream = object.getObjectContent();
		m3u8Download.downloadM3u8FileToLocal(inputStream.getDelegateStream(), video);
	}

	private List<SortBuilder<?>> sortBuilders(PageDTO<Video> page) {
		List<SortBuilder<?>> sortBuilders = Lists.newArrayList();
		List<OrderItem> orderItems = page.getOrders();
		if (CollectionUtil.isEmpty(orderItems)) {

			FieldSortBuilder fieldSortBuilder = SortBuilders.fieldSort("publishTime").order(SortOrder.DESC);
			sortBuilders.add(fieldSortBuilder);

//			ScoreSortBuilder scoreSortBuilder = SortBuilders.scoreSort().order(SortOrder.DESC);
//			sortBuilders.add(scoreSortBuilder);
		}

		for (OrderItem orderItem : orderItems) {
			String column = orderItem.getColumn();
			boolean asc = orderItem.isAsc();
			FieldSortBuilder fieldSortBuilder = SortBuilders.fieldSort(column);
			if (!asc) {
				fieldSortBuilder.order(SortOrder.DESC);
			}
			sortBuilders.add(fieldSortBuilder);
		}
	   return sortBuilders;
	}

	@Override
	public void syncToElasticsearch() {
		CompletableFuture<List<Video>> future = CompletableFuture.supplyAsync(this::list);
		future.thenAcceptAsync((videoList) -> {
			int batchSize = 1000;
			log.info("数据同步开始, 共{}条数据：", videoList.size());
			List<Video> videos = Lists.newArrayList();
			for (int i = 0; i < videoList.size(); i++) {
				if (i != 0 && i % batchSize != 0) {
					videos.add(videoList.get(i));
				}
				else {
					videoEsRepository.saveAll(videos);
					log.info("{}条数据同步成功", i);
					videos.clear();
				}
			}
			videoEsRepository.saveAll(videos);
			log.info("数据同步完成, 共{}条数据", videoList.size());
		});
	}

}
