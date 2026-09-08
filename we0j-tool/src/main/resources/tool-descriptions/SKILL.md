Load a skill's full instructions by name (progressive disclosure, stage 2).

The system prompt lists available skills with name and description only. When — and only
when — the current task clearly matches a skill's description, call this tool with that
skill's exact `name` to load its full instructions, which are returned wrapped in a
`<skill>` element. Follow the loaded instructions for the remainder of the task.

Do not load skills speculatively, do not load more than one skill unless the task requires
it, and never claim a capability from a skill you have not loaded. Optional `args` are
passed through to the skill instructions (free-form flags or parameters they define).
