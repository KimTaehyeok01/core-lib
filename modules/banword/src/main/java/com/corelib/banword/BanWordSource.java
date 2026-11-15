package com.corelib.banword;

import com.corelib.common.exception.CoreException;
import java.util.List;

/**
 * 금지어·화이트리스트 원문 데이터를 라이브러리에 공급하는 계약.
 *
 * <p>소비자가 이 인터페이스의 구현 빈을 등록해야 한다. 미등록 시 기동 실패
 * ({@link CoreException}, {@link BanWordError#SOURCE_NOT_REGISTERED}).
 *
 * <p>구현 방식 자유 — JPA/MyBatis/메모리/파일/HTTP/설정.
 * 라이브러리가 Trie 로 캐시하므로 호출 빈도는 "최초 매칭 + evict 후 다음 매칭".
 *
 * <h2>구현 지침</h2>
 * <ul>
 *   <li>반환 리스트는 변경 가능해야 함 (ArrayList 권장) — {@code new ArrayList<>(source)}</li>
 *   <li>예외는 {@link CoreException} 으로 래핑해 던질 것</li>
 *   <li>null 반환 금지 — 빈 데이터는 {@link java.util.Collections#emptyList()}</li>
 *   <li>다중 인스턴스 환경에서 데이터 변경 시 각 인스턴스의 evict API 호출 책임</li>
 * </ul>
 */
public interface BanWordSource {

	/** 금지어 원문 목록. null 금지, 빈 데이터는 빈 리스트. */
	List<String> getBanWords();

	/** 화이트리스트 원문 목록. null 금지, 빈 데이터는 빈 리스트. */
	List<String> getWhiteWords();
}
