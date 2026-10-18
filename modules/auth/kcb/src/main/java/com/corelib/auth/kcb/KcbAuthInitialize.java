// auth.kcb.cp-cd 가 있으면 설정·라이선스를 확인하고 KcbAuthUtils 를 초기화하는 자동설정
package com.corelib.auth.kcb;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** KCB jar 유무는 기동이 아니라 첫 호출 때 확인한다 — jar 를 넣기 전에도 앱은 뜬다 */
@AutoConfiguration
@EnableConfigurationProperties(KcbAuthProperty.class)
// auth.kcb.cp-cd 이 없거나 빈 값이면 자동설정을 끈다 — 키 없이도 앱은 뜨고, 호출 시 '미초기화' 에러로 드러난다
@ConditionalOnExpression("!'${auth.kcb.cp-cd:}'.isBlank()")
public class KcbAuthInitialize {

	public KcbAuthInitialize(KcbAuthProperty property) {
		property.validate();
		KcbAuthUtils.initialize(property, new KcbOkCertCaller());
	}
}
