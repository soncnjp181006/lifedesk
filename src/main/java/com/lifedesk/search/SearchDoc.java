package com.lifedesk.search;

/** One indexable document aggregated from any module. */
public record SearchDoc(String docId, String type, String title, String content, String tags, String refId) {
    public static final String TASK = "TASK";
    public static final String NOTE = "NOTE";
    public static final String TRANSACTION = "TRANSACTION";
    public static final String HABIT = "HABIT";
    public static final String GOAL = "GOAL";
    public static final String COURSE = "COURSE";
    public static final String FLASHCARD = "FLASHCARD";
    public static final String HEALTH = "HEALTH";
    public static final String WORKFLOW = "WORKFLOW";

    public static String displayType(String type) {
        return switch (type) {
            case TASK -> "Công việc";
            case NOTE -> "Ghi chú";
            case TRANSACTION -> "Giao dịch";
            case HABIT -> "Thói quen";
            case GOAL -> "Mục tiêu";
            case COURSE -> "Khóa học";
            case FLASHCARD -> "Flashcard";
            case HEALTH -> "Sức khỏe";
            case WORKFLOW -> "Automation";
            default -> type;
        };
    }
}
