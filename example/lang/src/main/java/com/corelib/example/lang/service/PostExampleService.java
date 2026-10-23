package com.corelib.example.lang.service;

import com.corelib.common.error.CommonErrorCode;
import com.corelib.common.exception.CoreException;
import com.corelib.common.util.ValidateUtils;
import com.corelib.example.lang.dto.PostExampleDto;
import com.corelib.lang.CollectionUtils;
import com.corelib.lang.MaskingUtils;
import com.corelib.lang.RandomStringUtils;
import com.corelib.lang.StringUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 비회원 게시판 서비스.
 * <ul>
 *   <li>{@link StringUtils} — 제목·작성자 공백 정리, 기본값(익명), 목록 요약 길이 제한</li>
 *   <li>{@link CollectionUtils} — 태그 목록 null 안전 처리</li>
 *   <li>{@link RandomStringUtils} — 공유 코드·삭제 PIN 발급 (SecureRandom)</li>
 *   <li>{@link MaskingUtils} — 작성자 이름·이메일 마스킹</li>
 * </ul>
 */
@Service
public class PostExampleService {

	private static final String ANONYMOUS = "익명";
	private static final int SHARE_CODE_LENGTH = 8;
	private static final int DELETE_PIN_LENGTH = 6;
	private static final int SUMMARY_LENGTH = 50;
	private static final int MAX_TAGS = 5;
	private static final int MAX_TAG_LENGTH = 20;

	/** 실서비스는 게시글 테이블 — 예제라 메모리에 둔다 */
	private final Map<String, PostExampleDto.Post> posts = new ConcurrentHashMap<>();

	public PostExampleDto.CreateResponse create(PostExampleDto.CreateRequest request) {
		ValidateUtils.validate(request);
		String deletePin = RandomStringUtils.randomNumeric(DELETE_PIN_LENGTH);
		PostExampleDto.Post post = PostExampleDto.Post.builder()
													  .shareCode(issueShareCode())
													  .title(request.getTitle().trim())
													  .content(request.getContent().strip())
													  .authorName(StringUtils.defaultIfBlank(StringUtils.trimToNull(request.getAuthorName()), ANONYMOUS))
													  .authorEmail(StringUtils.trimToNull(request.getAuthorEmail()))
													  .tags(normalizeTags(request.getTags()))
													  .deletePin(deletePin)
													  .createdAt(LocalDateTime.now())
													  .build();
		posts.put(post.getShareCode(), post);
		return PostExampleDto.CreateResponse.builder().shareCode(post.getShareCode()).deletePin(deletePin).build();
	}

	/** 최신순 목록 — tag 를 주면 그 태그 글만 */
	public List<PostExampleDto.PostSummary> list(String tag) {
		String filter = normalizeTag(tag);
		return posts.values().stream()
					.filter(post -> filter == null || post.getTags().contains(filter))
					.sorted(Comparator.comparing(PostExampleDto.Post::getCreatedAt).reversed())
					.map(post -> PostExampleDto.PostSummary.builder()
														   .shareCode(post.getShareCode())
														   .title(post.getTitle())
														   .summary(summarize(post.getContent()))
														   .authorName(displayName(post.getAuthorName()))
														   .tags(post.getTags())
														   .createdAt(post.getCreatedAt())
														   .build())
					.toList();
	}

	public PostExampleDto.PostDetail detail(String shareCode) {
		PostExampleDto.Post post = find(shareCode);
		return PostExampleDto.PostDetail.builder()
										.shareCode(post.getShareCode())
										.title(post.getTitle())
										.content(post.getContent())
										.authorName(displayName(post.getAuthorName()))
										.maskedAuthorEmail(MaskingUtils.email(post.getAuthorEmail()))
										.tags(post.getTags())
										.createdAt(post.getCreatedAt())
										.build();
	}

	/** 삭제 — 등록 때 받은 PIN 이 맞아야 한다 (상수 시간 비교) */
	public void delete(PostExampleDto.DeleteRequest request) {
		ValidateUtils.validate(request);
		PostExampleDto.Post post = find(request.getShareCode());
		boolean isMatched = MessageDigest.isEqual(post.getDeletePin().getBytes(StandardCharsets.UTF_8),
			request.getDeletePin().trim().getBytes(StandardCharsets.UTF_8));
		if (!isMatched) {
			throw new CoreException(CommonErrorCode.FORBIDDEN, "삭제 PIN 이 일치하지 않습니다.");
		}
		posts.remove(post.getShareCode());
	}

	/** 공백·빈값·중복 제거, 소문자 통일, 최대 5개 */
	private static List<String> normalizeTags(List<String> tags) {
		return CollectionUtils.emptyIfNull(tags).stream()
							  .map(PostExampleService::normalizeTag)
							  .filter(StringUtils::isNotBlank)
							  .distinct()
							  .limit(MAX_TAGS)
							  .toList();
	}

	private static String normalizeTag(String tag) {
		String value = StringUtils.trimToNull(tag);
		return value == null ? null : StringUtils.truncate(value.replace("#", "").toLowerCase(Locale.ROOT), MAX_TAG_LENGTH);
	}

	private static String summarize(String content) {
		String flat = content.replaceAll("\\s+", " ");
		return flat.length() > SUMMARY_LENGTH ? StringUtils.truncate(flat, SUMMARY_LENGTH) + "…" : flat;
	}

	/** 익명은 그대로, 실명은 마스킹 */
	private static String displayName(String authorName) {
		return ANONYMOUS.equals(authorName) ? ANONYMOUS : MaskingUtils.name(authorName);
	}

	/** 충돌하면 다시 뽑는다 (62^8 이라 사실상 한 번에 끝난다) */
	private String issueShareCode() {
		String code;
		do {
			code = RandomStringUtils.randomAlphanumeric(SHARE_CODE_LENGTH);
		} while (posts.containsKey(code));
		return code;
	}

	private PostExampleDto.Post find(String shareCode) {
		PostExampleDto.Post post = StringUtils.isBlank(shareCode) ? null : posts.get(shareCode.trim());
		if (post == null) {
			throw new CoreException(CommonErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다.");
		}
		return post;
	}
}
