package com.example.auditor.service.curriculum;

import com.example.auditor.domain.curriculum.Curriculum;
import com.example.auditor.domain.curriculum.Requirement;
//import com.example.auditor.domain.curriculum.Sortbyname;
import com.example.auditor.dto.RequirementDto;
import com.example.auditor.repository.curriculum.CurriculumRepository;
import com.example.auditor.repository.curriculum.RequirementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CurriculumImportService {

    @Value("${audit.parser.type-prefix}")
    private String requirementTypePrefix;

    @Value("${audit.parser.requirements-row-terminator}")
    private String requirementsRowTerminator;

    @Value("${audit.parser.transcript.regex.course-code}")
    public String courseCode;

    private final CurriculumRepository curriculumRepository;

    private final RequirementRepository requirementRepository;

    public Curriculum uploadCurriculum(Long id, MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Curriculum file must not be null or empty");
        }

        Curriculum curriculum = curriculumRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot find curriculum with specified id"));

        List<Requirement> requirements = new LinkedList<>();

        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {

            Sheet sheet = wb.getSheetAt(0);
            String lastRequirementType = null;

            for (int i = 0; i < sheet.getLastRowNum(); i++) {
                Cell firstColumnCell = sheet.getRow(i).getCell(0);

                String firstColumnValue = validateCellAndGetString(firstColumnCell, i);

                if (firstColumnValue.startsWith(requirementTypePrefix)) {
                    int prefixLength = requirementTypePrefix.length();
                    lastRequirementType = firstColumnValue.substring(prefixLength).strip();
                } else if (lastRequirementType == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requirements must be annotated by at least one category");
                } else if (firstColumnValue.startsWith(requirementsRowTerminator)) {
                    break;
                } else {

                    Cell secondColumnCell = sheet.getRow(i).getCell(1);
                    Integer credit = validateCellAndGetInteger(secondColumnCell, i);

                    String antipatterns = "";
                    Cell antipatternsCell = sheet.getRow(i).getCell(3);
                    if (antipatternsCell != null) {

                        antipatterns = antipatternsCell.getStringCellValue().strip();
                    }


                    Cell patternsCell = sheet.getRow(i).getCell(2);
                    String patterns = "";

                    if (patternsCell == null || patternsCell.getCellType() == CellType.BLANK) {
                        patterns = firstColumnValue.strip();
                        patterns = patterns.replaceAll("\\p{Pd}", "-");
                        if (patterns.contains("-")) {
                            patterns = patterns.split("-")[0].strip();
                        }
                    } else {

                        patterns = patternsCell.getStringCellValue().strip();
                    }



                    Requirement requirement = requirementRepository.save(Requirement.builder()
                            .credit(credit)
                            .antipatterns(antipatterns)
                            .patterns(patterns)
                            .name(firstColumnValue)
                            .type(lastRequirementType)
                            .build());
                    log.info("requirement: " + requirement);
                    requirements.add(requirement);

                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        curriculum.setRequirements(requirements);
        return curriculumRepository.save(curriculum);
    }

    public Curriculum addCurriculumPlan(Long id, MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Course plan file must not be null or empty");
        }

        Curriculum curriculum = curriculumRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot find curriculum with specified id"));

        List<Requirement> requirements = curriculum.getRequirements();
//        List<Requirement> requirementsCopy = new ArrayList<>(requirements);
//        List<Requirement> updatedRequirements = new ArrayList<>();

        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                List<XWPFTable> table = doc.getTables();

                int year = 0;
                boolean nextLineIsUseful = false;

                XWPFTable xwpfTable = table.get(0);
                    List<XWPFTableRow> row = xwpfTable.getRows();
                    for (XWPFTableRow xwpfTableRow : row) {
                        List<XWPFTableCell> cell = xwpfTableRow.getTableCells();
                        List<String> fiveLines = new ArrayList<>();
                        for (XWPFTableCell xwpfTableCell : cell) {
                            if(xwpfTableCell!=null)
                            {
                                String line = xwpfTableCell.getText();

                                if (line.startsWith("Total")) {
                                    nextLineIsUseful = false;
                                }
                                else if (line.equals("Freshman") || line.equals("Sophomore") || line.equals("Junior") || line.equals("Senior")) {
                                    nextLineIsUseful = true;
                                    fiveLines.add(line);
                                    year++;
                                    continue;
                                }
                                if (nextLineIsUseful) {
                                        fiveLines.add(line);
                                }
                            }
                        }
                        if (fiveLines.size() == 5) {
                            String[] name = {fiveLines.get(1).strip(), fiveLines.get(3).strip()};
                            Integer[] semester = {year * 2 - 1, year * 2};

                            Pattern p = Pattern.compile(courseCode, Pattern.MULTILINE);

                            for (int i = 0; i < name.length; i++) {
                                Matcher m = p.matcher(name[i]);
                                if (m.find()) {
                                    name[i] =  m.group();
                                }
                                    System.out.println(name[i] + '@' + semester[i]);
                            }

                            for (Requirement curReq : requirements) {
//                            for (Requirement curReq : requirementsCopy) {
//                            int index = 0;
//                            while (!requirementsCopy.isEmpty() &&  index < requirementsCopy.size()) {
//                                Requirement curReq = requirementsCopy.get(index);
                                String curReqName = curReq.getName().strip();
                                for (int i = 0; i < name.length; i++) {
                                    String s1 = name[i].length() > curReqName.length() ? name[i] : curReqName;
                                    String s2 = name[i].length() > curReqName.length() ? curReqName : name[i];
                                    System.out.println(s1 + ">>>" + s2);
                                    if (!s2.isEmpty() && s1.startsWith(s2)) {

                                        Requirement requirement = requirementRepository.save(Requirement.builder()
                                                .credit(curReq.getCredit())
                                                .antipatterns(curReq.getAntipatterns())
                                                .patterns(curReq.getPatterns())
                                                .name(curReq.getName())
                                                .type(curReq.getType())
                                                .semester(semester[i])
                                                .build());
                                        log.info("requirement: " + requirement);

                                        requirements.set(requirements.indexOf(curReq), requirement);
//                                        requirements.set(requirementsCopy.indexOf(curReq), requirement);
//                                        updatedRequirements.add(requirement);
//                                        requirementsCopy.remove(curReq);
                                        break;
                                    }
                                }
//                            index++;

                            }
                        }
                    }

        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

//        Collections.sort(requirements, new Sortbyname());
//        curriculum.setRequirements(updatedRequirements);
        curriculum.setRequirements(requirements);
        return curriculumRepository.save(curriculum);
    }


    public Curriculum bindRequirements(Long id, List<RequirementDto> dtos) {

        Curriculum curriculum = curriculumRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot find curriculum with specified id"));

        List<Requirement> previousRequirements = curriculum.getRequirements();

        List<Requirement> requirements = new LinkedList<>();

        for (RequirementDto dto : dtos) {

            Requirement requirement = requirementRepository.save(
                    Requirement.builder()
                            .credit(dto.getCredit())
                            .name(dto.getName())
                            .type(dto.getType())
                            .patterns(dto.getPatterns())
                            .antipatterns(dto.getAntipatterns())
                    .build()
            );

            System.out.println(requirement);

            requirements.add(requirement);
        }

        curriculum.setRequirements(requirements);
        // requirementRepository.deleteAll(previousRequirements);

        return curriculumRepository.save(curriculum);
    }


    private String validateCellAndGetString(Cell cell, int i) {
        if (cell == null || cell.getCellType() != CellType.STRING) {
            String message = "Expected String type of cell at row: "
                    + (i + 1) + "but found ";

            message += cell == null
                    ? "null"
                    : cell.getCellType().name();

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        } else {
            return cell.getStringCellValue();
        }

    }

    private Integer validateCellAndGetInteger(Cell cell, int i) {
        if (cell == null || cell.getCellType() != CellType.NUMERIC) {
            String message = "Expected Integer type of cell at row: "
                    + (i + 1) + "but found ";

            message += cell == null
                    ? "null"
                    : cell.getCellType().name();

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        try {
            return (Integer) (int) cell.getNumericCellValue();
        } catch (ClassCastException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected Integer type of cell at row: " + (i + 1));
        }
    }




}
