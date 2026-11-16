package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Disclosure questions and answers, and evidence requirements and receipts, for applications (NS-IL01-003). */
public final class ApplicationRecords {

    private ApplicationRecords() {
    }

    @Entity(name = "ApplicationQuestion")
    @Table(name = "app_disclosure_question")
    public static class Question {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(length = 100)
        private String product;

        @Column(nullable = false, length = 30)
        private String code;

        @Column(nullable = false, length = 300)
        private String question;

        @Column(name = "answer_type", nullable = false, length = 10)
        private String answerType;

        @Column(nullable = false)
        private int mandatory;

        @Column(name = "display_order", nullable = false)
        private int displayOrder;

        protected Question() {
        }

        public String getCode() {
            return code;
        }

        public String getQuestion() {
            return question;
        }

        public String getAnswerType() {
            return answerType;
        }

        public boolean isMandatory() {
            return mandatory == 1;
        }
    }

    /** The answer given, with the question as it was asked. */
    @Entity(name = "ApplicationAnswer")
    @Table(name = "app_disclosure_answer")
    public static class Answer {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "application_ref", nullable = false, length = 20)
        private String applicationRef;

        @Column(nullable = false, length = 30)
        private String code;

        @Column(nullable = false, length = 300)
        private String question;

        @Column(nullable = false, length = 500)
        private String answer;

        @Column(name = "answered_by", nullable = false, length = 100)
        private String answeredBy;

        @Column(name = "answered_at", nullable = false)
        private LocalDateTime answeredAt;

        protected Answer() {
        }

        Answer(String applicationRef, Question question, String answer, String answeredBy) {
            this.applicationRef = applicationRef;
            this.code = question.getCode();
            this.question = question.getQuestion();
            this.answer = answer;
            this.answeredBy = answeredBy;
            this.answeredAt = LocalDateTime.now();
        }

        void change(String answer, String answeredBy) {
            this.answer = answer;
            this.answeredBy = answeredBy;
            this.answeredAt = LocalDateTime.now();
        }

        public String getCode() {
            return code;
        }

        public String getAnswer() {
            return answer;
        }
    }

    @Entity(name = "ApplicationEvidenceRequirement")
    @Table(name = "app_evidence_requirement")
    public static class EvidenceRequirement {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(length = 100)
        private String product;

        @Column(name = "document_type", nullable = false, length = 60)
        private String documentType;

        @Column(nullable = false)
        private int mandatory;

        @Column(name = "min_sum_assured", precision = 19, scale = 2)
        private BigDecimal minSumAssured;

        @Column(name = "satisfied_by", length = 20)
        private String satisfiedBy;

        @Column(name = "display_order", nullable = false)
        private int displayOrder;

        protected EvidenceRequirement() {
        }

        public String getDocumentType() {
            return documentType;
        }

        public boolean isMandatory() {
            return mandatory == 1;
        }

        public BigDecimal getMinSumAssured() {
            return minSumAssured;
        }

        public String getSatisfiedBy() {
            return satisfiedBy;
        }
    }

    /** Evidence recorded as received for an application, with who checked it. */
    @Entity(name = "ApplicationEvidence")
    @Table(name = "app_evidence")
    public static class Evidence {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "application_ref", nullable = false, length = 20)
        private String applicationRef;

        @Column(name = "document_type", nullable = false, length = 60)
        private String documentType;

        @Column(nullable = false, length = 300)
        private String note;

        @Column(name = "recorded_by", nullable = false, length = 100)
        private String recordedBy;

        @Column(name = "recorded_at", nullable = false)
        private LocalDateTime recordedAt;

        protected Evidence() {
        }

        Evidence(String applicationRef, String documentType, String note, String recordedBy) {
            this.applicationRef = applicationRef;
            this.documentType = documentType;
            this.note = note;
            this.recordedBy = recordedBy;
            this.recordedAt = LocalDateTime.now();
        }

        public String getDocumentType() {
            return documentType;
        }

        public String getNote() {
            return note;
        }

        public String getRecordedBy() {
            return recordedBy;
        }

        public LocalDateTime getRecordedAt() {
            return recordedAt;
        }
    }
}
