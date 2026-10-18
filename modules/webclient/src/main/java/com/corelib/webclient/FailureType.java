// 외부 API 호출 실패 분류 - 결과확정(후속조치 판단 가능)과 결과불명(상대 처리 여부 모름)
package com.corelib.webclient;

public enum FailureType {

	/** 4xx — 상대가 처리를 거절했다(결과확정). 본문의 벤더 오류코드를 확인한다 */
	REJECTED,

	/** 연결 실패(DNS·TCP·TLS·connect timeout) — 요청이 나가지 않았다(결과확정) */
	NOT_SENT,

	/** 읽기 timeout·5xx·본문 해석 실패 — 상대가 처리했는지 알 수 없다(결과불명). 조회·망취소로 확인한다 */
	UNKNOWN;

	/** @return 상대의 처리 결과가 확정됐으면 true (REJECTED, NOT_SENT) */
	public boolean isConfirmed() {
		return this != UNKNOWN;
	}
}
