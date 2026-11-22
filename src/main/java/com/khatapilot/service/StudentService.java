package com.khatapilot.service;

import com.khatapilot.model.Student;
import com.khatapilot.repository.StudentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    @Transactional(readOnly = true)
    public List<Student> listAll() {
        return studentRepository.findAll();
    }

    @Transactional
    public Student create(String name, BigDecimal monthlyFee, String notes) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Student name is required");
        }
        if (monthlyFee == null || monthlyFee.signum() <= 0) {
            throw new IllegalArgumentException("Monthly fee must be greater than zero");
        }
        return studentRepository.save(new Student(name.trim(), monthlyFee, notes));
    }
}
