package com.spring.aichat.domain.common;

import org.springframework.beans.DirectFieldAccessor;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * [V42 · 2026-11-09] 필드 단위 시드 잠금.
 *
 * <p>공식 캐릭터·세계관은 부팅마다 YAML 시드로 덮인다. 어드민이 바꾼 <b>필드만</b> 잠그고(엔티티 필드명 CSV),
 * 시더는 시드를 적용하되 잠긴 필드 값은 원래대로 되돌려 둔다 — 잠그지 않은 필드(백오피스가 못 고치는
 * 의상/장소 키·일러 태그 포함)는 계속 YAML을 따른다. 행 전체 잠금은 토글 하나로 그 행의 YAML 갱신을
 * 전부 멈추게 해서 폐기했다(2026-11-09 리뷰).
 */
public final class SeedLocks {

    private SeedLocks() {}

    public static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 기존 잠금에 필드를 더한 CSV(정렬). 결과가 비면 null. */
    public static String merge(String csv, Collection<String> fields) {
        Set<String> all = new TreeSet<>(parse(csv));
        all.addAll(fields);
        return all.isEmpty() ? null : String.join(",", all);
    }

    /** seed 적용 후 잠긴 필드만 적용 전 값으로 복원한다. 필드명은 엔티티 Java 필드명이어야 한다. */
    public static void applyPreservingLocked(Object entity, Set<String> locked, Runnable applySeed) {
        if (locked.isEmpty()) {
            applySeed.run();
            return;
        }
        DirectFieldAccessor accessor = new DirectFieldAccessor(entity);
        Map<String, Object> snapshot = new HashMap<>();
        for (String field : locked) {
            if (accessor.isReadableProperty(field)) snapshot.put(field, accessor.getPropertyValue(field));
        }
        applySeed.run();
        snapshot.forEach(accessor::setPropertyValue);
    }
}
