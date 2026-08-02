# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`.
- **Read an issue**: `gh issue view <number> --comments`, including labels when relevant.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments` with appropriate label and state filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`.
- **Apply or remove labels**: `gh issue edit <number> --add-label "..."` or `--remove-label "..."`.
- **Close an issue**: `gh issue close <number> --comment "..."`.

Infer the repository from `git remote -v`; `gh` does this automatically inside the clone.

## Pull requests as a triage surface

**PRs as a request surface: no.**

GitHub shares one number space across issues and PRs. Resolve an ambiguous number with `gh pr view <number>` and fall back to `gh issue view <number>`.

## Publishing and fetching

When a skill says to publish to the issue tracker, create a GitHub issue. When it says to fetch a ticket, run `gh issue view <number> --comments`.

## Wayfinding operations

The Wayfinder map is one issue labelled `wayfinder:map`; its decision tickets are GitHub sub-issues.

- **Map**: create one issue containing Destination, Notes, Decisions so far, Not yet specified, and Out of scope.
- **Child ticket**: create an issue labelled `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, or `wayfinder:task`, then attach it through GitHub's sub-issues endpoint.
- **Sub-issue fallback**: if sub-issues are unavailable, add the child to a task list in the map and put `Part of #<map>` at the top of the child body.
- **Blocking**: use GitHub's native issue dependencies. Add a blocker with `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where the value is the blocker's database `id`, not its issue number or `node_id`.
- **Blocking fallback**: if native dependencies are unavailable, put `Blocked by: #<number>` at the top of the child body.
- **Frontier**: list the map's open children in map order and exclude assigned children and children whose `issue_dependencies_summary.blocked_by` is greater than zero.
- **Claim**: assign the ticket to the driving developer before work with `gh issue edit <number> --add-assignee @me`.
- **Resolve**: post the answer as a resolution comment, close the ticket, then append a one-line gist and link to the map's Decisions so far.

In human-facing text, refer to maps and tickets by linked title rather than by a bare issue number.
