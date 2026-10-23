package com.corelib.auth.kcb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.error.ErrorCode;
import com.corelib.common.exception.CoreException;
import java.nio.file.Files;
import java.nio.file.Path;
import kcb.module.v3.OkCert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KcbAuthUtilsTest {

	@TempDir
	Path tempDir;

	private KcbAuthProperty property;

	@BeforeEach
	void setUp() throws Exception {
		OkCert.reset();
		Path license = Files.writeString(tempDir.resolve("V00000000000_license.dat"), "license");
		property = new KcbAuthProperty();
		property.setCpCd("V00000000000");
		property.setLicensePath(license.toString());
		property.setReturnUrl("https://example.com/kcb/return");
		property.setSiteName("코어랩");
		property.setSiteUrl("example.com");
		property.validate();
		KcbAuthUtils.initialize(property, new KcbOkCertCaller());
	}

	private static ErrorCode errorCodeOf(Throwable e) {
		return ((CoreException) e).getErrorCode();
	}

	@Test
	@DisplayName("팝업 시작 - IDS_HS_POPUP_START 호출 인자, 팝업 form 값(tc 고정·cp_cd·mdl_tkn)")
	void start() {
		OkCert.nextResponse = "{\"RSLT_CD\":\"B000\",\"RSLT_MSG\":\"정상\",\"MDL_TKN\":\"tkn-1\"}";

		KcbAuthDto.StartResponse response = KcbAuthUtils.start(null);

		assertThat(OkCert.lastSvcName).isEqualTo("IDS_HS_POPUP_START");
		assertThat(OkCert.lastTarget).isEqualTo("PROD");
		assertThat(OkCert.lastCpCd).isEqualTo("V00000000000");
		assertThat(OkCert.lastLicense).isEqualTo(property.getLicensePath());
		assertThat(OkCert.lastRequest).contains("\"RETURN_URL\":\"https://example.com/kcb/return\"", "\"SITE_NAME\":\"코어랩\"",
			"\"SITE_URL\":\"example.com\"", "\"RQST_CAUS_CD\":\"00\"");
		assertThat(response.getPopupUrl()).isEqualTo(KcbAuthProperty.DEFAULT_POPUP_URL);
		assertThat(response.formFields())
			.containsEntry("tc", KcbAuthDto.POPUP_TC)
			.containsEntry("cp_cd", "V00000000000")
			.containsEntry("mdl_tkn", "tkn-1")
			.containsEntry("target_id", "");
	}

	@Test
	@DisplayName("팝업 시작 거절 - KCB_{RSLT_CD} 패스스루")
	void start_rejected() {
		OkCert.nextResponse = "{\"RSLT_CD\":\"B123\",\"RSLT_MSG\":\"회원사 정보 오류\"}";

		assertThatThrownBy(() -> KcbAuthUtils.start(null))
			.hasMessage("회원사 정보 오류")
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("KCB_B123");
	}

	@Test
	@DisplayName("결과 확인 - IDS_HS_POPUP_RESULT, 소문자 b000 도 성공, 필드 매핑 + 원본, 개인정보 toString 마스킹")
	void confirm() {
		OkCert.nextResponse = "{\"RSLT_CD\":\"b000\",\"RSLT_NAME\":\"홍길동\",\"RSLT_BIRTHDAY\":\"19900101\",\"RSLT_SEX_CD\":\"M\","
			+ "\"RSLT_NTV_FRNR_CD\":\"L\",\"CI\":\"CI-1\",\"DI\":\"DI-1\",\"TEL_COM_CD\":\"01\",\"TEL_NO\":\"01012345678\",\"EXTRA\":\"x\"}";

		KcbAuthDto.ConfirmResponse response = KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn("tkn-1").build());

		assertThat(OkCert.lastSvcName).isEqualTo("IDS_HS_POPUP_RESULT");
		assertThat(OkCert.lastRequest).isEqualTo("{\"MDL_TKN\":\"tkn-1\"}");
		assertThat(response.getName()).isEqualTo("홍길동");
		assertThat(response.getBirthday()).isEqualTo("19900101");
		assertThat(response.getCi()).isEqualTo("CI-1");
		assertThat(response.getDi()).isEqualTo("DI-1");
		assertThat(response.getTelNo()).isEqualTo("01012345678");
		assertThat(response.getRaw()).containsEntry("EXTRA", "x");
		assertThat(response.toString()).doesNotContain("홍길동", "01012345678", "CI-1");
	}

	@Test
	@DisplayName("결과에 CI·DI 없음 - RESULT_INCOMPLETE")
	void confirm_incomplete() {
		OkCert.nextResponse = "{\"RSLT_CD\":\"B000\",\"RSLT_NAME\":\"홍길동\"}";

		assertThatThrownBy(() -> KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn("tkn-1").build()))
			.extracting(KcbAuthUtilsTest::errorCodeOf)
			.isEqualTo(KcbAuthError.RESULT_INCOMPLETE);
	}

	@Test
	@DisplayName("결과 RSLT_CD 없음 - 성공으로 보지 않음 (KCB_UNKNOWN)")
	void confirm_missingCode() {
		OkCert.nextResponse = "{\"CI\":\"CI-1\",\"DI\":\"DI-1\"}";

		assertThatThrownBy(() -> KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn("tkn-1").build()))
			.extracting(e -> errorCodeOf(e).code())
			.isEqualTo("KCB_UNKNOWN");
	}

	@Test
	@DisplayName("모듈 내부 오류 - EXTERNAL_ERROR, 원래 메시지(요청값 포함 가능)는 노출 안 함")
	void moduleError() {
		OkCert.nextError = new IllegalStateException("요청값 01012345678 처리 실패");

		assertThatThrownBy(() -> KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn("tkn-1").build()))
			.satisfies(e -> {
				assertThat(errorCodeOf(e)).isEqualTo(KcbAuthError.EXTERNAL_ERROR);
				assertThat(e.getMessage()).doesNotContain("01012345678");
			});
	}

	@Test
	@DisplayName("KCB jar 없음 - MODULE_NOT_FOUND (설치 안내 메시지)")
	void moduleNotFound() {
		KcbAuthUtils.initialize(property, new KcbOkCertCaller("kcb.module.v3.Missing"));

		assertThatThrownBy(() -> KcbAuthUtils.start(null))
			.satisfies(e -> {
				assertThat(errorCodeOf(e)).isEqualTo(KcbAuthError.MODULE_NOT_FOUND);
				assertThat(e.getMessage()).contains("OkCert3 jar");
			});
	}

	@Test
	@DisplayName("mdl_tkn 누락 - BAD_REQUEST (모듈 호출 안 함)")
	void confirm_validation() {
		assertThatThrownBy(() -> KcbAuthUtils.confirm(KcbAuthDto.ConfirmRequest.builder().mdlTkn(" ").build()))
			.extracting(KcbAuthUtilsTest::errorCodeOf)
			.isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(OkCert.lastSvcName).isNull();
	}

	@Test
	@DisplayName("설정 검증 - 라이선스 파일 없음 LICENSE_NOT_FOUND, 회원사코드 없음 설정 오류")
	void propertyValidate() {
		property.setLicensePath(tempDir.resolve("missing.dat").toString());
		assertThatThrownBy(property::validate)
			.extracting(KcbAuthUtilsTest::errorCodeOf).isEqualTo(KcbAuthError.LICENSE_NOT_FOUND);

		property.setCpCd(null);
		assertThatThrownBy(property::validate)
			.extracting(KcbAuthUtilsTest::errorCodeOf).isEqualTo(CommonErrorCode.INTERNAL_SERVER);
	}
}
