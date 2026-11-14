package com.studentprep.exam.dto;

public class StudentProfileDto {
    private String name;
    private String registrationNumber;

    public StudentProfileDto(String name, String registrationNumber) {
        this.name = name;
        this.registrationNumber = registrationNumber;
    }

    public String getName() { return name; }
    public String getRegistrationNumber() { return registrationNumber; }
}
