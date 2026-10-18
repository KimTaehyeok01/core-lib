// 테스트용 가짜 KCB OkCert 모듈 - 실제 jar 와 같은 클래스명·메서드 시그니처로 리플렉션 호출 경로를 검증
package kcb.module.v3;

public class OkCert {

	public static volatile String lastTarget;
	public static volatile String lastCpCd;
	public static volatile String lastSvcName;
	public static volatile String lastLicense;
	public static volatile String lastRequest;
	public static volatile String nextResponse;
	public static volatile RuntimeException nextError;

	public static void reset() {
		lastTarget = null;
		lastCpCd = null;
		lastSvcName = null;
		lastLicense = null;
		lastRequest = null;
		nextResponse = null;
		nextError = null;
	}

	public String callOkCert(String target, String cpCd, String svcName, String license, String request) {
		lastTarget = target;
		lastCpCd = cpCd;
		lastSvcName = svcName;
		lastLicense = license;
		lastRequest = request;
		if (nextError != null) {
			throw nextError;
		}
		return nextResponse;
	}
}
