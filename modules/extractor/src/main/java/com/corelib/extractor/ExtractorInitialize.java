package com.corelib.extractor;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * extractor 자동 구성 — 모든 빈이 만들어진 뒤 OKT 사전을 미리 한 번 로드해 첫 호출 지연을 제거한다.
 * 상태를 가지지 않으며 별도의 StartupReport 도 두지 않는다 (정적 유틸만 제공).
 */
@AutoConfiguration
public class ExtractorInitialize implements SmartInitializingSingleton {

	@Override
	public void afterSingletonsInstantiated() {
		// OKT 사전 워밍업 — normalize 1 회 호출로 사전/모델이 메모리에 올라온다
		KeywordExtractorUtils.normalize("워밍업");
	}
}
