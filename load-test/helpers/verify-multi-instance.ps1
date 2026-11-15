function Assert-MultiInstanceInventory {
    param($Record, [int]$InitialStock, [int]$Quantity, [int]$Confirmed, [string]$Mode, [int]$Attempts)
    if ($Attempts -lt 1 -or $Confirmed -lt 0 -or $Confirmed -gt $Attempts) {
        throw 'Invalid purchase attempt/success counts'
    }
    foreach ($field in @('orders','keys','units','stock','node0_orders','node1_orders')) {
        if ($null -eq $Record -or [string]$Record.$field -notmatch '^\d+$') {
            throw "Missing/invalid persisted database count: $field"
        }
    }
    if ($null -eq $Record -or [int]$Record.orders -ne $Confirmed -or [int]$Record.keys -ne $Confirmed -or
        [int]$Record.units -ne $Confirmed * $Quantity -or [int]$Record.stock -lt 0 -or
        [int]$Record.node0_orders + [int]$Record.node1_orders -ne $Confirmed) {
        throw 'Persisted orders/units/keys do not match successful HTTP purchases'
    }
    if ($Mode -ne 'NONE' -and ([int]$Record.units -gt $InitialStock -or
        [int]$Record.stock -ne $InitialStock - [int]$Record.units)) {
        throw 'Safe mode violated inventory conservation'
    }
    if ($Mode -in @('ATOMIC', 'PESSIMISTIC', 'REDIS') -and
        $Confirmed -ne [math]::Min($Attempts, [math]::Floor($InitialStock / $Quantity))) {
        throw 'Blocking/atomic mode did not sell the expected available units'
    }
}
