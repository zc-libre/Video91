package com.libre.video.core.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.libre.core.toolkit.CollectionUtil;
import com.libre.core.toolkit.Exceptions;
import com.libre.core.toolkit.StreamUtils;
import com.libre.video.core.event.VideoDownloadEvent;
import com.libre.video.core.event.VideoSaveEvent;
import com.libre.video.mapper.VideoMapper;
import com.libre.video.pojo.ErrorVideo;
import com.libre.video.pojo.Video;
import com.libre.video.service.ErrorVideoService;
import com.libre.video.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventListener {

	private final VideoService videoService;
	private final SqlSessionTemplate sqlSessionTemplate;
	private final ErrorVideoService errorVideoService;

	@Async("taskScheduler")
	@EventListener(VideoSaveEvent.class)
	@Transactional(rollbackFor = Exception.class)
	public void onSaveEvent(VideoSaveEvent videoSaveEvent) {
		List<Video> videoList = videoSaveEvent.getVideoList();
		if (CollectionUtil.isEmpty(videoList)) {
			return;
		}
		log.info("start save videos.....");
		try {
			Video video = videoList.get(0);
			saveOrUpdateBatch(videoList, video.getVideoWebsite());
		} catch (Exception e) {
			log.error("保存数据失败: {}", Exceptions.getStackTraceAsString(e));
		}
	}

	@Async("taskScheduler")
	@EventListener(ErrorVideo.class)
	public void onErrorEvent(ErrorVideo errorVideo) {
		log.info("start save error video, errorType: {}", errorVideo.getType());
		errorVideoService.save(errorVideo);
	}

	@Async("downloadExecutor")
	@EventListener(VideoDownloadEvent.class)
	public void onDownloadEvent(VideoDownloadEvent downloadEvent) {
		videoService.saveVideoToOss(downloadEvent.getVideo());
	}

	@Transactional(rollbackFor = Exception.class)
	public void saveOrUpdateBatch(List<Video> videoList, int type) {
		List<Long> videoIds = StreamUtils.list(videoList, Video::getVideoId);
		List<Video> videos = videoService.list(Wrappers.<Video>lambdaQuery().eq(Video::getVideoWebsite, type).in(Video::getVideoId, videoIds));
		if (CollectionUtil.isEmpty(videos)) {
			videoService.saveBatch(videoList);
			return;
		}
		Map<Long, Video> videoMap = StreamUtils.map(videos, Video::getVideoId, Function.identity());
		SqlSessionFactory sqlSessionFactory = sqlSessionTemplate.getSqlSessionFactory();
		SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
		VideoMapper videoMapper = sqlSession.getMapper(VideoMapper.class);

		videoList.forEach(video -> {
			Video dbVideo = videoMap.get(video.getVideoId());
			if (Objects.isNull(dbVideo)) {
				videoMapper.insert(video);
			} else {
				video.setId(dbVideo.getId());
				videoMapper.updateById(video);
			}
		});
		sqlSession.commit();
	}
}
