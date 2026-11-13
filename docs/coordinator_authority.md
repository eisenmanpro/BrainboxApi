# Grade-coordinator authority matrix

A **grade coordinator** is a class teacher with grade-wide duties: exactly one coordinator per
grade per school (enforced by `CoordinatorGradeGuard`). Their elevated coordination authority is
limited to **their own school and their own grade** (`users.grade_level`). A platform `ADMIN`
and the school's `ICT_ADMIN` stay school-wide; every other staff member sees only what their
own classes/learner relationships allow.

Implemented by [GradeAuthority.kt](../src/main/kotlin/com/afrithecus/brainbox/api/identity/GradeAuthority.kt).
Services must check in this order and never trust a client-supplied grade or school:

1. `isPlatformAdmin` / `isSchoolWide` — ADMIN (any school), ICT_ADMIN (own school).
2. `isInSchool` — never cross a tenant, even with a matching grade string.
3. assignment-based access the service already implements (class owner, shared class/learner).
4. `overseesGrade` / `canReachClass` / `canReachLearner` — coordinator grade scope.

Assignment-based teaching access is **additive, never replaced**: a coordinator who teaches a
class in another grade keeps that ordinary teaching access there, but gains no coordination
rights over that grade. A coordinator with no grade on record (legacy row) has no grade-wide
powers until an admin sets the grade.

## Matrix

"Coordinator (own grade)" means `user.gradeLevel == resource.gradeLevel` in the same school.

| Capability (server surface) | ADMIN | ICT_ADMIN | Coordinator (own grade) | Coordinator (other grade) | CTEACHER / TEACHER |
|---|---|---|---|---|---|
| Student analytics (`studentanalytics/*`) | all | school | own grade | teaching access only | teaching access only |
| Exam analytics (`analytics/*`) | all | school | own grade | teaching access only | teaching access only |
| Gradebook (`gradebook/*`) | all | school | own grade | own class only | own class only |
| Attendance read/write (`attendance/*`) | all | school | own grade + own class | own class only | no register filing |
| CBC reports/strand detail (`cbcratings/*`) | all | school | own grade | teaching access only | teaching access only |
| Paper exams (`traditional/*`): list | all | school | own grade + taught grades | taught grades only | taught grades only |
| Paper exams: create/edit/lifecycle/finalize/publish | all | school | own grade | **refused** | no |
| Paper exams: edit-request approval/grants | all | school | own grade exam | **refused** | no |
| Paper exams: grade subject/grading config | all | school | own grade | **refused** | no |
| Reports hub submit/history/job access | all | school | own grade (class/exam/learner scope) | **refused** | own job only |
| Student report requests | all | school | own grade | teaching access only | teaching access only |
| Teacher dashboard exam counts | all | school | own grade only | excluded | excluded |
| Learner approval/rejection | all | school | own grade learner | teaching access only | joined/class learner |
| Subscription/billing view | all | school (learners) | own grade learner | **refused** | not other users |
| Roster-only learner provisioning | all | school | own grade class | own class only | own class only |
| Class transitions (`classes/transition`) | all | school | move touches own grade, or own target class | own target class only | own target class only |
| Class-transition **on/off switch** | all | school | **school-wide (documented exception)** | — | no |
| Schedule-change review (`timetable/schedule-changes`) | all | school | own grade's teacher requests | **refused** | own requests only |
| Messaging / class chat / announcements | role-scoped | role-scoped | role-scoped (unchanged) | role-scoped | role-scoped |
| School administration (`/admin/schools/**`) | all | own school | no | no | no |
| Platform console (`/admin/console/**`) | platform permissions | no | no | no | no |

## Deliberate decisions and exceptions

- **Class-transition switch stays coordinator-owned.** It is a single school-level operational
  setting with no learner data and the product roadmap assigns it to coordinators
  (`product_ops_roadmap` item 4). It is the one school-wide coordinator capability kept; it is
  recorded here rather than silently widened.
- **Blank report templates** (`GET teacher/reports/templates`) are available to coordinators for
  every report type: the response renders an empty branded template and contains no learner data.
- **Messaging, class chat and announcements** remain role-scoped for all staff and are not
  grade-filtered: they are person-to-person/audience channels, not a grade data grant. A
  coordinator still cannot read another grade's marks, rosters, analytics or reports.
- **Report schedules** remain owner-scoped (create/list/update/delete are per account). A
  scheduled run re-enters the same grade-scoped report data services, so a schedule pointing at
  another grade cannot render that grade's data — it fails the job instead. No extra gate is
  added on the schedule row because grade-level scheduled reports carry no `classId` to scope.
- **School administration and the platform console** stay behind `AdminSchoolAccess`
  (ADMIN / ICT_ADMIN) and platform permissions; the coordinator change does not touch them.
- **Platform console teacher creation** may still create a coordinator for a school; that is a
  platform-level operation, guarded by the same one-per-grade rule, not school data access.
- **Teaching access through class ownership** is checked by the services themselves after the
  authority predicates, so it keeps working for coordinators in any grade.

## Tests

- `CoordinatorGradeScopeTests` (identity package) — end-to-end service-level proof for student &
  exam analytics, CBC, gradebook, attendance read/write, paper exams, report hub, student
  reports, dashboard counts, learner approval, billing, provisioning and schedule-change review:
  own grade allowed, other grade refused, teaching access preserved, ICT admin school-wide.
- `ClassTransitionTests` — coordinator move inside their grade allowed, outside refused, own
  target class preserved.
- `TeacherTimetableWebTests`, `ReportWebTests`, `TraditionalExamWebTests`,
  `GradebookWebTests`, `RosterOnlyLearnerTests`, `TeacherAuthWebTests`,
  `TeacherDashboardWebTests` — existing web suites updated only where the intended semantics
  changed (a coordinator fixture now carries its grade).
