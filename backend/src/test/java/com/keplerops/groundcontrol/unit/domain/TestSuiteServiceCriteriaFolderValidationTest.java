package com.keplerops.groundcontrol.unit.domain;

import static com.keplerops.groundcontrol.TestUtil.setField;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.keplerops.groundcontrol.domain.exception.NotFoundException;
import com.keplerops.groundcontrol.domain.projects.model.Project;
import com.keplerops.groundcontrol.domain.projects.service.ProjectService;
import com.keplerops.groundcontrol.domain.requirements.repository.RequirementRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseFolderRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestCaseRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteMemberRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteRepository;
import com.keplerops.groundcontrol.domain.testcases.repository.TestSuiteSourceRequirementRepository;
import com.keplerops.groundcontrol.domain.testcases.service.CreateTestSuiteCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestSuiteCriteriaCommand;
import com.keplerops.groundcontrol.domain.testcases.service.TestSuiteService;
import com.keplerops.groundcontrol.domain.testcases.state.TestSuitePopulationMode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Split from TestSuiteServiceTest under issue #1467 for the 500-LOC limit
 * (docs/CODING_STANDARDS.md). Test bodies are unchanged; fixtures are
 * repeated because JUnit builds a fresh instance per test class. */
@ExtendWith(MockitoExtension.class)
class TestSuiteServiceCriteriaFolderValidationTest {
    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestSuiteMemberRepository memberRepository;

    @Mock
    private TestSuiteSourceRequirementRepository sourceRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseFolderRepository folderRepository;

    @Mock
    private RequirementRepository requirementRepository;

    @Mock
    private com.keplerops.groundcontrol.domain.testcases.repository.TestRunRepository testRunRepository;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private TestSuiteService testSuiteService;

    private Project project;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        project = new Project("ground-control", "Ground Control");
        projectId = UUID.randomUUID();
        setField(project, "id", projectId);
    }

    @Nested
    class CriteriaFolderValidation {

        @Test
        void rejectsCriteriaFolderThatDoesNotExist() {
            when(projectService.getById(projectId)).thenReturn(project);
            when(testSuiteRepository.existsByProjectIdAndUid(projectId, "TS-Q-NF"))
                    .thenReturn(false);
            UUID missingFolderId = UUID.randomUUID();
            when(folderRepository.findById(missingFolderId)).thenReturn(Optional.empty());
            var cmd = new CreateTestSuiteCommand(
                    projectId,
                    "TS-Q-NF",
                    "n",
                    null,
                    TestSuitePopulationMode.QUERY_BASED,
                    new TestSuiteCriteriaCommand(null, null, null, null, missingFolderId, null));

            assertThatThrownBy(() -> testSuiteService.create(cmd))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining(missingFolderId.toString());
        }
    }
}
