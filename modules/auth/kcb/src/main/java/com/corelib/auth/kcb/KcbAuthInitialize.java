package com.corelib.auth.kcb;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** KCB jar 유무는 기동이 아니라 첫 호출 때 확인한다 — jar 를 넣기 전에도 앱은 뜬다 */
@AutoConfiguration
@EnableConfigurationProperties(KcbAuthProperty.class)
// auth.kcb.cp-cd 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
@ConditionalOnExpression("!'${auth.kcb.cp-cd:}'.isBlank()")
public class KcbAuthInitialize implements DisposableBean {

	private final KcbOkCertCaller okCertCaller;

	public KcbAuthInitialize(KcbAuthProperty property) {
		property.validate();
		this.okCertCaller = new KcbOkCertCaller(property.getTimeout());
		KcbAuthUtils.initialize(property, okCertCaller);
	}

	/** 호출 전용 스레드 풀을 닫는다 — 컨텍스트 재시작(테스트·devtools) 때 스레드가 쌓이지 않게 */
	@Override
	public void destroy() {
		okCertCaller.shutdown();
	}
}
