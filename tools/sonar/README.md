# Sonar configuration artifacts

Canonical store for SonarCloud configuration captured from Ground Control and
sibling repos. Add new Sonar-related artifacts here so every Sonar config a
Ground Control engineer needs is in one place.

The scanner config (`sonar-project.properties`) stays at the repository root
because the Sonar scanner reads it from there. Everything else, including
quality profile backups, custom-rule definitions, and any future tooling
artifacts, lives under this directory.

## Layout

- `profiles/`: quality profile XML backups exported via
  `GET /api/qualityprofiles/backup`. One file per profile per capture date.

## File naming

Profile backups use `<language>__<profile-name>__<org-key>__<YYYY-MM-DD>.xml`.
The org segment identifies the SonarCloud organization the profile was
captured from; two orgs can hold profiles with the same name and language,
and the org segment keeps them distinct. The date is the capture date, not a
release date. Adding a new snapshot does not replace prior captures; both
coexist so changes to a profile remain auditable.

## Capturing a new snapshot

Export the live profile from SonarCloud (requires `SONAR_TOKEN` in the
environment):

```bash
curl -s -u "$SONAR_TOKEN:" -G \
  "https://sonarcloud.io/api/qualityprofiles/backup" \
  --data-urlencode "organization=<org>" \
  --data-urlencode "language=<lang>" \
  --data-urlencode "qualityProfile=<name>" \
  -o "tools/sonar/profiles/<lang>__<name>__<org>__$(date +%F).xml"
```

## Restoring a profile into another project

Upload the XML to the target SonarCloud organization via
`POST /api/qualityprofiles/restore`, then attach it to a project with
`POST /api/qualityprofiles/add_project`, or mark it as the organization
default for the language. The captured `<language>__<profile-name>` pair in
the filename matches what SonarCloud expects in the restore payload.
