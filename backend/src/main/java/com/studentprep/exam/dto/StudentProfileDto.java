package com.studentprep.exam.dto;

public class StudentProfileDto {
    private String name;
    private String registrationNumber;
    private String examCenter;

    public StudentProfileDto(String name, String registrationNumber, String examCenter) {
        this.name = name;
        this.registrationNumber = registrationNumber;
        this.examCenter = examCenter;
    }

    public String getName() { return name; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getExamCenter() { return examCenter; }
}
