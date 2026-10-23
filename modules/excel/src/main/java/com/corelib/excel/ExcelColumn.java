package com.corelib.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엑셀·CSV 열 — DTO 필드에 붙인다. 붙지 않은 필드는 쓰지도 읽지도 않는다.
 * 지원 타입: String · Integer/int · Long/long · Double/double · BigDecimal · Boolean/boolean · LocalDate · LocalDateTime · enum
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExcelColumn {

	/** 헤더 이름 — 읽을 때도 이 이름으로 열을 찾는다 (순서 무관) */
	String header();

	/** 쓰기 순서 — 작은 값이 왼쪽. 같으면 필드 선언 순서 */
	int order() default Integer.MAX_VALUE;

	/** 열 너비(글자 수) */
	int width() default 15;

	/** 날짜·숫자 표시 형식 (예: yyyy-MM-dd, #,##0) — 비우면 타입 기본값 */
	String format() default "";

	/** true 면 읽을 때 빈 칸을 오류로 */
	boolean required() default false;
}
