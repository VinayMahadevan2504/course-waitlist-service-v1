package org.com.dcp.waitlist;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A learner who can enroll in a {@link CourseOffering}.
 * {@code completedCourseIds} is used to evaluate the prerequisite rule.
 */
public final class Learner {

    private final String id;
    private final String name;
    private final Set<String> completedCourseIds;

    public Learner(String id, String name, Set<String> completedCourseIds) {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Learner id must not be blank");
        }
        this.id = id;
        this.name = name == null ? id : name;
        this.completedCourseIds = completedCourseIds == null
                ? Collections.emptySet()
                : new HashSet<>(completedCourseIds);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean hasCompleted(String courseId) {
        return completedCourseIds.contains(courseId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Learner learner)) return false;
        return id.equals(learner.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Learner{id='" + id + "'}";
    }
}