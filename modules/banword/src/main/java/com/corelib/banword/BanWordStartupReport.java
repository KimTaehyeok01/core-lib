package com.corelib.banword;

import com.corelib.common.startup.StartupReport;
import com.corelib.common.startup.StartupReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * banword 기동 안내 — Source FQCN / 캐시 모드 / max-text-length 를 한 줄로 보고한다.
 */
@AutoConfiguration(after = BanWordInitialize.class)
public class BanWordStartupReport implements StartupReporter {

	private static final String NAME = "BanWord";

	private final ObjectProvider<BanWordProperty> property;
	private final ObjectProvider<BanWordSource> source;
	private final ObjectProvider<BanWordCacheService> cacheService;

	public BanWordStartupReport(ObjectProvider<BanWordProperty> property,
		ObjectProvider<BanWordSource> source,
		ObjectProvider<BanWordCacheService> cacheService) {
		this.property = property;
		this.source = source;
		this.cacheService = cacheService;
	}

	@Override
	public StartupReport report() {
		BanWordProperty prop = property.getIfAvailable();
		BanWordSource src = source.getIfAvailable();
		if (prop == null || src == null) {
			return StartupReport.notReady(NAME, "BanWordProperty 또는 BanWordSource 빈이 없습니다.");
		}
		String cacheMode;
		BanWordCacheService cs = cacheService.getIfAvailable();
		if (cs instanceof BanWordRedisService) {
			cacheMode = "Redis(CacheErrorHandler 미등록 — Redis 장애 시 금지어 검사 500)";
		} else {
			cacheMode = "Direct";
		}
		return StartupReport.ready(NAME,
			"source=" + src.getClass().getName() + ", cache=" + cacheMode + ", maxTextLength=" + prop.getMaxTextLength());
	}
}
