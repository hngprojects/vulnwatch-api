
using Domain.Enums;
using Microsoft.AspNetCore.Mvc;

namespace Application.Features.Scans.DTOs;

public class GetScanHistoryRequest
{
    [FromQuery(Name = "status")]
    public ScanStatus? Status { get; set; }

    [FromQuery(Name = "coverage")]
    public ScanCoverage? Coverage { get; set; }

    [FromQuery(Name = "sort_by")]
    public string SortBy { get; set; } = "created_at";

    [FromQuery(Name = "order")]
    public string Order { get; set; } = "asc";

    [FromQuery(Name = "page")]
    public int Page { get; set; } = 1;

    [FromQuery(Name = "page_size")]
    public int PageSize { get; set; } = 10;

    private static readonly HashSet<string> ValidSortFields = ["created_at", "status"];

    public bool IsValid(out string? error)
    {
        if (PageSize > 50) { error = "Invalid query parameters"; return false; }
        if (!ValidSortFields.Contains(SortBy)) { error = "Invalid query parameters"; return false; }
        if (Order is not "asc" and not "desc") { error = "Invalid query parameters"; return false; }
        error = null;
        return true;
    }
}