---
name: skill-creator
description: Create, edit, improve, or audit AgentSkills. Use when creating a new skill from scratch or when asked to improve, review, audit, tidy up, or clean up an existing skill or SKILL.md file.
---

# Skill Creator

## About Skills
Skills are modular, self-contained packages that extend agent capabilities by providing specialized knowledge, workflows, and tools.

### What Skills Provide
1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key
The context window is a public good. Only add context the agent doesn't already have.
Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom
- High freedom (text-based instructions): Multiple approaches valid
- Medium freedom (pseudocode/scripts with parameters): Preferred pattern exists
- Low freedom (specific scripts, few parameters): Operations are fragile

### Anatomy of a Skill
```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/          - Executable code
    ├── references/       - Documentation for context
    └── assets/           - Files used in output
```

### Progressive Disclosure Design
1. Metadata (name + description) - Always in context (~100 words)
2. SKILL.md body - When skill triggers (<5k words)
3. Bundled resources - As needed (unlimited)

Keep SKILL.md body under 500 lines. Split content into separate files when approaching this limit.

## Skill Creation Process
1. Understand the skill with concrete examples
2. Plan reusable skill contents (scripts, references, assets)
3. Initialize the skill
4. Edit the skill (implement resources and write SKILL.md)
5. Package the skill
6. Iterate based on real usage

### Frontmatter
- `name`: The skill name
- `description`: Primary triggering mechanism. Include what the Skill does AND when to use it.

### Skill Naming
- Lowercase letters, digits, and hyphens only
- Prefer short, verb-led phrases
- Name the skill folder exactly after the skill name
