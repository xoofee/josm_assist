# Repository instructions

## Build and test follow-through

- When asked to build or install, run the requested build command.
- If the build or tests fail, investigate and fix failures caused by this repository, then rerun the requested command. Do not stop after reporting the first failure when it can be resolved within the task.
- Report any remaining failure only after reasonable in-scope fixes and reruns.
- After completing every execution plan, run `./build.sh --install` from the
  repository root. If build or installation fails, investigate and fix
  repository-caused errors, then rerun `./build.sh --install` before declaring
  the plan complete.

## Execution plans

- Every execution plan must be stored under `docs/exec-plans/` and named using
  the pattern `<four-digit-sequence>-<descriptive-kebab-case-slug>.md`, for
  example `0001-simplify-way-2-service-scope.md`.
- Use the next unused sequence number for each new plan; do not reuse an
  existing number.
