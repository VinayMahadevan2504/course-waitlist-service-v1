package org.com.dcp.waitlist;

import java.util.Objects;

public final class CourseOffering {

    private final String id;
    private final String title;
    private final int capacity;
    private final String prerequisiteCourseId; // nullable: no prerequisite required

    public CourseOffering(String id, String title, int capacity, String prerequisiteCourseId) {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Course id must not be blank");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must not be negative");
        }
        this.id = id;
        this.title = title == null ? id : title;
        this.capacity = capacity;
        this.prerequisiteCourseId = prerequisiteCourseId;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public int getCapacity() {
        return capacity;
    }

    /** Eligibility policy: no prerequisite, or the learner has completed it. */
    public boolean isEligible(Learner learner) {
        Objects.requireNonNull(learner, "learner must not be null");
        return prerequisiteCourseId == null || learner.hasCompleted(prerequisiteCourseId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CourseOffering that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "CourseOffering{id='" + id + "', capacity=" + capacity + "}";
    }
}