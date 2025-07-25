package com.utez.edu.sigeabackend.modules.services;

import com.utez.edu.sigeabackend.modules.entities.*;
import com.utez.edu.sigeabackend.modules.entities.dto.groupDtos.GroupRequestDto;
import com.utez.edu.sigeabackend.modules.entities.dto.groupDtos.GroupResponseDto;
import com.utez.edu.sigeabackend.modules.repositories.CareerRepository;
import com.utez.edu.sigeabackend.modules.repositories.CurriculumRepository;
import com.utez.edu.sigeabackend.modules.repositories.GroupRepository;
import com.utez.edu.sigeabackend.modules.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private final GroupRepository repository;
    private final UserRepository userRepository;
    private final CareerRepository careerRepository;
    private final CurriculumRepository curriculumRepository;

    public GroupService(GroupRepository repository, UserRepository userRepository, CareerRepository careerRepository, CurriculumRepository curriculumRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.careerRepository = careerRepository;
        this.curriculumRepository = curriculumRepository;
    }

    /**
     * Transforma una entidad GroupEntity en su DTO de respuesta.
     */
    private GroupResponseDto toResponseDto(GroupEntity g) {
        return new GroupResponseDto(
                g.getId(),
                g.getName(),
                g.getWeekDay().name(),
                g.getStartTime().toString(),
                g.getEndTime().toString(),
                g.getTeacher().getId(),
                g.getTeacher().getName() + " " + g.getTeacher().getPaternalSurname(),
                g.getCareer().getId(),
                g.getCareer().getName(),
                g.getCurriculum().getId(),
                g.getCurriculum().getName()
        );
    }

    /**
     * Rellena (o actualiza) un GroupEntity a partir de un GroupRequestDto validado,
     * junto con las entidades ya recuperadas de Teacher y Career.
     */
    private void populateFromDto(
            GroupEntity target,
            GroupRequestDto dto,
            UserEntity teacher,
            CareerEntity careerEntity,
            CurriculumEntity curriculum
    ) {
        target.setName(dto.name());
        target.setStartTime(LocalTime.parse(dto.startTime()));
        target.setEndTime(LocalTime.parse(dto.endTime()));
        target.setWeekDay(WeekDays.valueOf(dto.weekDay()));
        target.setTeacher(teacher);
        target.setCareer(careerEntity);
        target.setCurriculum(curriculum);
    }

    /**
     * Valida que el horario sea lógico
     */
    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        if (endTime.isBefore(startTime) || endTime.equals(startTime)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La hora de fin debe ser posterior a la hora de inicio"
            );
        }
    }

    /**
     * Valida que el docente no tenga conflictos de horario
     */
    private void validateTeacherScheduleConflict(Long teacherId, WeekDays weekDay,
                                                 LocalTime startTime, LocalTime endTime, Long excludeGroupId) {

        List<GroupEntity> teacherGroups = repository.findByTeacherId(teacherId);

        for (GroupEntity existingGroup : teacherGroups) {
            if (excludeGroupId != null && Objects.equals(existingGroup.getId(), excludeGroupId)) {
                continue;
            }

            if (existingGroup.getWeekDay().equals(weekDay)) {
                LocalTime existingStart = existingGroup.getStartTime();
                LocalTime existingEnd = existingGroup.getEndTime();

                boolean hasConflict = !(endTime.isBefore(existingStart) ||
                        endTime.equals(existingStart) ||
                        startTime.isAfter(existingEnd) ||
                        startTime.equals(existingEnd));

                if (hasConflict) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            String.format("El docente ya tiene asignado el grupo '%s' el día %s de %s a %s",
                                    existingGroup.getName(),
                                    existingGroup.getWeekDay().name(),
                                    existingStart.toString(),
                                    existingEnd.toString())
                    );
                }
            }
        }
    }

    // LISTAR TODOS
    @Transactional(readOnly = true)
    public ResponseEntity<List<GroupResponseDto>> findAllGroups() {
        List<GroupEntity> groups = repository.findAll();
        if (groups.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<GroupResponseDto> dtos = groups.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // LISTAR POR DOCENTE
    @Transactional(readOnly = true)
    public ResponseEntity<List<GroupResponseDto>> findGroupsByTeacher(long teacherId) {
        List<GroupEntity> groups = repository.findByTeacherId(teacherId);
        if (groups.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<GroupResponseDto> dtos = groups.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // LISTAR POR CARRERA
    @Transactional(readOnly = true)
    public ResponseEntity<List<GroupResponseDto>> findGroupsByCareer(long careerId) {
        List<GroupEntity> groups = repository.findByCareerId(careerId);
        if (groups.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<GroupResponseDto> dtos = groups.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // OBTENER UNO POR ID
    @Transactional(readOnly = true)
    public ResponseEntity<GroupResponseDto> findById(long id) {
        return repository.findById(id)
                .map(this::toResponseDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // CREAR NUEVO GRUPO
    @Transactional
    public ResponseEntity<GroupResponseDto> create(GroupRequestDto dto) {
        // Buscar entidades relacionadas
        UserEntity teacher = userRepository.findById(dto.teacherId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Docente no encontrado con id " + dto.teacherId()
                ));

        CareerEntity careerEntity = careerRepository.findById(dto.careerId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Carrera no encontrada con id " + dto.careerId()
                ));

        CurriculumEntity curriculum = curriculumRepository.findById(dto.curriculumId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Plan de estudios no encontrado con id " + dto.curriculumId()
                ));

        // Parsear y validar horarios
        LocalTime startTime = LocalTime.parse(dto.startTime());
        LocalTime endTime = LocalTime.parse(dto.endTime());
        WeekDays weekDay = WeekDays.valueOf(dto.weekDay());

        validateTimeRange(startTime, endTime);
        validateTeacherScheduleConflict(dto.teacherId(), weekDay, startTime, endTime, null);

        // Crear y guardar el grupo
        GroupEntity g = new GroupEntity();
        populateFromDto(g, dto, teacher, careerEntity, curriculum);

        GroupEntity saved = repository.save(g);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseDto(saved));
    }

    // ACTUALIZAR GRUPO - CORREGIDO
    @Transactional
    public ResponseEntity<GroupResponseDto> update(long id, GroupRequestDto dto) {
        GroupEntity existing = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Grupo no encontrado con id " + id
                ));

        UserEntity teacher = userRepository.findById(dto.teacherId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Docente no encontrado con id " + dto.teacherId()
                ));

        CareerEntity careerEntity = careerRepository.findById(dto.careerId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Carrera no encontrada con id " + dto.careerId()
                ));

        CurriculumEntity curriculum = curriculumRepository.findById(dto.curriculumId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Plan de estudios no encontrado con id " + dto.curriculumId()
                ));

        // Parsear y validar horarios
        LocalTime startTime = LocalTime.parse(dto.startTime());
        LocalTime endTime = LocalTime.parse(dto.endTime());
        WeekDays weekDay = WeekDays.valueOf(dto.weekDay());

        validateTimeRange(startTime, endTime);
        validateTeacherScheduleConflict(dto.teacherId(), weekDay, startTime, endTime, id);

        populateFromDto(existing, dto, teacher, careerEntity, curriculum);

        GroupEntity updated = repository.save(existing);
        return ResponseEntity.ok(toResponseDto(updated));
    }

    // ELIMINAR
    @Transactional
    public ResponseEntity<Void> delete(long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}