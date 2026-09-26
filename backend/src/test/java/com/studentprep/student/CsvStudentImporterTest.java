package com.studentprep.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.studentprep.questionbank.SubjectRepository;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CsvStudentImporterTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CsvStudentImporter csvStudentImporter;

    @Test
    void testExportStudentsCsv() {
        when(studentRepository.findAll()).thenReturn(java.util.Collections.emptyList());

        byte[] result = csvStudentImporter.exportStudentsCsv();
        
        assertNotNull(result);
        assertTrue(new String(result).contains("Name,Registration Number"));
    }
}
