// 컬렉션/맵 null·빈값 처리 공통 유틸
package com.corelib.lang;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class CollectionUtils {

	private CollectionUtils() {
	}

	/** null 이거나 원소가 없으면 true */
	public static boolean isEmpty(Collection<?> collection) {
		return collection == null || collection.isEmpty();
	}

	/** 원소가 하나 이상 있으면 true */
	public static boolean isNotEmpty(Collection<?> collection) {
		return !isEmpty(collection);
	}

	/** null 이거나 항목이 없으면 true */
	public static boolean isEmpty(Map<?, ?> map) {
		return map == null || map.isEmpty();
	}

	/** 항목이 하나 이상 있으면 true */
	public static boolean isNotEmpty(Map<?, ?> map) {
		return !isEmpty(map);
	}

	/** null 이면 빈 불변 리스트 — for-each 전에 null 체크를 없앤다 */
	public static <T> List<T> emptyIfNull(List<T> list) {
		return list == null ? List.of() : list;
	}
}
