// 각 모듈이 기동 안내에 자기 초기화 결과를 알리는 인터페이스
package com.corelib.common.startup;

/**
 * 모듈별 초기화 결과 제공자 — 빈으로 등록하면 {@link StartupLogPrinter} 가 모아서 찍는다.
 * 설정이 없어 모듈이 꺼졌을 때도 등록돼 있어야 "미초기화" 를 알릴 수 있다 (조건 없는 자동설정에 둔다).
 */
@FunctionalInterface
public interface StartupReporter {

	StartupReport report();
}
