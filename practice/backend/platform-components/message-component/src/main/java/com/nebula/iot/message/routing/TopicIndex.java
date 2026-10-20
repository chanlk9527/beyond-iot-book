package com.nebula.iot.message.routing;

import com.nebula.iot.message.TopicPattern;
import java.util.*;

/** Build privately, then publish the immutable snapshot through a volatile reference. */
public final class TopicIndex<T> {
    private final Node<T> root = new Node<>();
    public void add(String pattern, T value) {
        TopicPattern.validatePattern(pattern);
        Node<T> node = root;
        for (String segment : pattern.split("/")) {
            if (segment.equals("**")) { node.remainder.add(value); return; }
            node = node.children.computeIfAbsent(segment, ignored -> new Node<>());
        }
        node.exact.add(value);
    }
    public Set<T> match(String topic) {
        TopicPattern.validate(topic);
        Set<T> result = new LinkedHashSet<>();
        find(root, topic.split("/"), 0, result);
        return result;
    }
    private void find(Node<T> node, String[] segments, int offset, Set<T> result) {
        result.addAll(node.remainder);
        if (offset == segments.length) { result.addAll(node.exact); return; }
        Node<T> exact = node.children.get(segments[offset]);
        if (exact != null) find(exact, segments, offset + 1, result);
        Node<T> wildcard = node.children.get("*");
        if (wildcard != null) find(wildcard, segments, offset + 1, result);
    }
    private static final class Node<T> {
        final Map<String, Node<T>> children = new HashMap<>();
        final Set<T> exact = new LinkedHashSet<>(), remainder = new LinkedHashSet<>();
    }
}
