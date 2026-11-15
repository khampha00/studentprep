package com.studentprep.exam;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ExamDeadlineExpiredException extends ResponseStatusException {

    private final String code;

    public ExamDeadlineExpiredException(String message) {
        this(message, "EXAM_WINDOW_EXPIRED");
    }

    public ExamDeadlineExpiredException(String message, String code) {
        super(HttpStatus.GONE, message);
        this.code = code != null ? code : "EXAM_WINDOW_EXPIRED";
    }

    public String getCode() {
        return code;
    }
}
