package com.teamsoda.globalprice.search;

import java.util.List;

/**
 * 사전(search_aliases)에 없는 한글 단어를 영어로 바꾸는 외부 번역기 (실제 구현: {@link DeeplTranslator}).
 * 결과는 {@link QueryNormalizer} 가 search_aliases 에 AUTO 로 저장해 다음부터는 부르지 않는다.
 */
public interface QueryTranslator {

    /** 번역기 없음 (키가 없거나 테스트) */
    QueryTranslator NONE = new QueryTranslator() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public List<String> translate(List<String> words) {
            throw new IllegalStateException("번역기가 꺼져 있음");
        }
    };

    boolean enabled();

    /** 한국어 단어들 → 영어. 같은 순서 · 같은 개수로 돌려준다 (못 바꾼 항목은 null). 호출이 실패하면 예외 */
    List<String> translate(List<String> words);
}
