package com.studentprep.student;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.studentprep.questionbank.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationSlipGeneratorTest {

    @Mock
    private StudentRepository studentRepository;

    private RegistrationSlipGenerator registrationSlipGenerator;

    @BeforeEach
    void setUp() {
        registrationSlipGenerator = new RegistrationSlipGenerator(studentRepository);
    }

    @Test
    void getRegistrationSlipPdf_RedactsCredentialStatusAndNeverExposesHashesOrPins() throws Exception {
        UUID studentId = UUID.randomUUID();
        Subject physics = new Subject("PHYSICS");
        physics.setId(UUID.randomUUID());

        Student student = new Student();
        student.setId(studentId);
        student.setName("Chidi Okonkwo");
        student.setRegistrationNumber("JAMB-2026-SLIP01");
        student.setExamCenter("Abuja CBT Hub");
        student.setPinHash("$argon2id$v=19$m=16384,t=2,p=1$salt$sensitivehashvalue");
        student.setPinResetRequired(false);
        student.setSubjects(Set.of(physics));

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        byte[] pdfBytes = registrationSlipGenerator.getRegistrationSlipPdf(studentId);
        assertThat(pdfBytes).isNotEmpty();

        String pdfText;
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            pdfText = extractor.getTextFromPage(1);
        }

        assertThat(pdfText).contains("Chidi Okonkwo");
        assertThat(pdfText).contains("JAMB-2026-SLIP01");
        assertThat(pdfText).contains("Abuja CBT Hub");
        assertThat(pdfText).contains("PHYSICS");
        assertThat(pdfText).contains("Login PIN: Issued separately (hashed)");
        assertThat(pdfText).contains("use Admin PIN Reset to issue a new one-time PIN");
        assertThat(pdfText).doesNotContain("$argon2id$");
        assertThat(pdfText).doesNotContain("sensitivehashvalue");
    }

    @Test
    void getRegistrationSlip_MetadataOmitsPinAndPinHash() {
        UUID studentId = UUID.randomUUID();
        Student student = new Student();
        student.setId(studentId);
        student.setName("Ngozi Eze");
        student.setRegistrationNumber("JAMB-2026-SLIP02");
        student.setExamCenter("Lagos Center");
        student.setPinHash("!LEGACY_INVALIDATED!");
        student.setPinResetRequired(true);

        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        Map<String, Object> slip = registrationSlipGenerator.getRegistrationSlip(studentId);

        assertThat(slip).containsEntry("name", "Ngozi Eze");
        assertThat(slip).containsEntry("registrationNumber", "JAMB-2026-SLIP02");
        assertThat(slip).containsEntry("pinResetRequired", true);
        assertThat(slip).doesNotContainKeys("pin", "pinHash");
        assertThat(String.valueOf(slip.get("credentialStatus")))
                .contains("Issued separately (hashed)");
    }
}
