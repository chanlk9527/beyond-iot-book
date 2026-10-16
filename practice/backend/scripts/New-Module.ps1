[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$')]
    [string]$Name,
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z][a-z0-9]*$')]
    [string]$PackageSegment
)

$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)
$backendRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$templateRoot = Join-Path $backendRoot 'module-template'
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $backendRoot $Name))
$parentPath = Join-Path $backendRoot 'pom.xml'

if ($Name -match '^(con|prn|aux|nul|com[1-9]|lpt[1-9])$') { throw 'Reserved Windows directory name.' }
$keywords = @('abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','true','false','null','template','bootstrap')
if ($PackageSegment -in $keywords) { throw 'Package segment is reserved.' }
if ([System.IO.Path]::GetDirectoryName($targetRoot) -ne $backendRoot) { throw 'Target must be directly inside backend.' }
if (Test-Path -LiteralPath $targetRoot) { throw "Module directory already exists: $targetRoot" }

function Read-TemplateText([string]$Path) {
    try { return [System.IO.File]::ReadAllText($Path, $utf8) }
    catch [System.Management.Automation.MethodInvocationException] {
        # Corporate encryption can expose different process-level views.
        $lines = @(rg --no-heading --no-line-number '^' -- $Path)
        if ($LASTEXITCODE -ne 0) { throw "Cannot read template: $Path" }
        return ($lines -join "`n") + "`n"
    }
}

$parentText = Read-TemplateText $parentPath
if (([regex]::Matches($parentText, '</modules>')).Count -ne 1) { throw 'Parent must have exactly one modules section.' }
if ($parentText.Contains("<module>$Name</module>")) { throw 'Module is already registered.' }
Push-Location -LiteralPath $backendRoot
try { $otherPackages = @(rg --files --no-ignore -g 'package-info.java' -g '!**/target/**') }
finally { Pop-Location }
foreach ($packageFile in $otherPackages) {
    $packageText = Read-TemplateText (Join-Path $backendRoot $packageFile)
    if ($packageText -match "(?m)^package com\.nebula\.iot\.$PackageSegment\.") { throw 'Package namespace is already in use.' }
}
Push-Location -LiteralPath $templateRoot
try { $files = @(rg --files --no-ignore -g '!target/**') }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'No template files found.' }
$prepared = @{}
foreach ($file in $files) {
    $relative = $file
    $relative = $relative.Replace(('template' + [System.IO.Path]::DirectorySeparatorChar), ($PackageSegment + [System.IO.Path]::DirectorySeparatorChar))
    $text = Read-TemplateText (Join-Path $templateRoot $file)
    $text = $text.Replace('com.nebula.iot.template', "com.nebula.iot.$PackageSegment")
    $text = $text.Replace('<artifactId>module-template</artifactId>', "<artifactId>$Name</artifactId>")
    $text = $text.Replace('<name>module-template</name>', "<name>$Name</name>")
    $text = $text.Replace('Business module scaffold; not a runtime dependency', "Business module: $Name")
    if ($relative -eq 'README.md') {
        $text = "# $Name`n`nNamespace: com.nebula.iot.$PackageSegment`n`nInherits nebula-platform-parent. See [module conventions](../module-template/README.md).`n`nDeclare runtime dependencies explicitly in platform-bootstrap when implementation is ready.`n"
    }
    $prepared[(Join-Path $targetRoot $relative)] = $text
}

$updatedParent = $parentText.Replace('    </modules>', "        <module>$Name</module>`n    </modules>")
if ($updatedParent -eq $parentText) { throw 'Unexpected parent modules formatting.' }

if ($PSCmdlet.ShouldProcess($targetRoot, 'Create module and register it in parent reactor')) {
    foreach ($entry in $prepared.GetEnumerator()) {
        [System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($entry.Key)) | Out-Null
        [System.IO.File]::WriteAllText($entry.Key, $entry.Value, $utf8)
    }
    [System.IO.File]::WriteAllText($parentPath, $updatedParent, $utf8)
    Write-Output "Created $Name; package com.nebula.iot.$PackageSegment. Run mvn verify from backend."
}
