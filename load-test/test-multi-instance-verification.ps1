$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/helpers/verify-multi-instance.ps1"
$valid = [pscustomobject]@{ orders=3; keys=3; units=9; stock=1; node0_orders=2; node1_orders=1 }
Assert-MultiInstanceInventory $valid 10 3 3 'ATOMIC' 20
$rejected = 0
foreach ($change in @(@{stock=2}, @{units=10}, @{orders=4}, @{keys=2}, @{node0_orders=0})) {
    $bad = [pscustomobject]@{ orders=3; keys=3; units=9; stock=1; node0_orders=2; node1_orders=1 }
    foreach ($entry in $change.GetEnumerator()) { $bad.($entry.Key) = $entry.Value }
    try { Assert-MultiInstanceInventory $bad 10 3 3 'ATOMIC' 20 } catch { $rejected++ }
}
if ($rejected -ne 5) { throw "Verifier accepted invalid database evidence: $rejected / 5 rejected" }
Assert-MultiInstanceInventory ([pscustomobject]@{orders=2;keys=2;units=2;stock=8;node0_orders=1;node1_orders=1}) 10 1 2 'OPTIMISTIC' 20
Assert-MultiInstanceInventory ([pscustomobject]@{orders=2;keys=2;units=2;stock=0;node0_orders=1;node1_orders=1}) 1 1 2 'NONE' 20
$acceptedBlank = $false
try {
    Assert-MultiInstanceInventory ([pscustomobject]@{orders='';keys='';units='';stock='';node0_orders='';node1_orders=''}) 0 1 0 'ATOMIC' 20
    $acceptedBlank = $true
} catch {}
if ($acceptedBlank) { throw 'Verifier accepted blank database numbers as zero' }
Assert-MultiInstanceInventory ([pscustomobject]@{orders=2;keys=2;units=2;stock=8;node0_orders=1;node1_orders=1}) 10 1 2 'ATOMIC' 2
'Verifier checks passed: multi-unit conservation, partial optimistic success, isolated NONE, 5 rejected corruptions, blank fields and low demand'
