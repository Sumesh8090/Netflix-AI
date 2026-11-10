package com.watchspaces.core;

import java.util.List;

/**
 * One retrievable metadata entry from a title's timeline (variation points are not retrievable).
 *
 * @param id       stable id cited in answers, e.g. "evt_2291"
 * @param endTs    end of the window the entry is "active" (e.g. a character on screen); may be null
 */
public record KnowledgeEntry(String id, Type type, int startTs, Integer endTs,
                             String title, String text, List<String> keywords) {

    public enum Type { SCENE, CHARACTER, TRIVIA, GLOSSARY }
}
