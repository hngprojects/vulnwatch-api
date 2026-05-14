
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.WebUtilities;


namespace Domain.Common;

public sealed record PageLinks(string Self, string? Next, string? Prev);

public class PagedResult<T>
{
    public IReadOnlyList<T> Data { get; init; } = Array.Empty<T>();
    public int TotalCount { get; init; }
    public int Page { get; init; }
    public int PageSize { get; init; }
    public int TotalPages { get; init; }
    public PageLinks Links { get; init; } = default!;

    public static PagedResult<T> From(
        IReadOnlyList<T> data,
        int totalCount,
        int page,
        int pageSize,
        string basePath,
        string? queryString)
    {
        if (page < 1) throw new ArgumentOutOfRangeException(nameof(page));
        if (pageSize <= 0) throw new ArgumentOutOfRangeException(nameof(pageSize));

        var totalPages = (int)Math.Ceiling((double)totalCount / pageSize);

        var filtered = QueryHelpers.ParseQuery(queryString?.TrimStart('?'))
            .Where(kvp => kvp.Key != "page" && kvp.Key != "limit")
            .SelectMany(kvp => kvp.Value, (kvp, v) => $"{kvp.Key}={Uri.EscapeDataString(v ?? "")}")
            .ToList();

        var qs = filtered.Count > 0 ? "&" + string.Join("&", filtered) : "";

        string Build(int p) => $"{basePath}?page={p}&limit={pageSize}{qs}";

        return new PagedResult<T>
        {
            Data = data,
            TotalCount = totalCount,
            Page = page,
            PageSize = pageSize,
            TotalPages = totalPages,
            Links = new PageLinks(
                Self: Build(page),
                Next: page < totalPages ? Build(page + 1) : null,
                Prev: page > 1 ? Build(page - 1) : null
            )
        };
    }
}