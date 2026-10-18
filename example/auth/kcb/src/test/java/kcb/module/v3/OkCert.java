// 예제 테스트용 가짜 KCB OkCert 모듈 - 실제 jar 와 같은 클래스명·메서드 시그니처
package kcb.module.v3;

public class OkCert {

	public static volatile String lastSvcName;
	public static volatile String lastRequest;

	/** 서비스명별로 정해진 응답을 돌려준다 */
	public String callOkCert(String target, String cpCd, String svcName, String license, String request) {
		lastSvcName = svcName;
		lastRequest = request;
		if ("IDS_HS_POPUP_START".equals(svcName)) {
			return "{\"RSLT_CD\":\"B000\",\"RSLT_MSG\":\"정상\",\"MDL_TKN\":\"tkn-1\"}";
		}
		if (request.contains("\"tkn-1\"")) {
			return person("CI-1", "19900101", "01012345678");
		}
		if (request.contains("\"tkn-again\"")) {
			return person("CI-1", "19900101", "01099998888");    // 같은 사람, 번호 변경
		}
		if (request.contains("\"tkn-child\"")) {
			return person("CI-CHILD", java.time.LocalDate.now().minusYears(10).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), "01011112222");
		}
		return "{\"RSLT_CD\":\"B209\",\"RSLT_MSG\":\"유효하지 않은 토큰\"}";
	}

	private static String person(String ci, String birthday, String telNo) {
		return "{\"RSLT_CD\":\"B000\",\"RSLT_NAME\":\"홍길동\",\"RSLT_BIRTHDAY\":\"" + birthday + "\",\"RSLT_SEX_CD\":\"M\","
			+ "\"RSLT_NTV_FRNR_CD\":\"L\",\"CI\":\"" + ci + "\",\"DI\":\"DI-1\",\"TEL_COM_CD\":\"01\",\"TEL_NO\":\"" + telNo + "\"}";
	}
}
