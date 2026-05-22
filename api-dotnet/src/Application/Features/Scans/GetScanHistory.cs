using Application.Interfaces;
using Domain.Common;
using Domain.Enums;
using MediatR;
using Microsoft.AspNetCore.Http;

namespace Application.Features.Scans;

public record ScanSummary(
    Guid ScanId,
    Guid? DomainId,
    string DomainName,
    ScanStatus Status,
    string RiskLevel,
    ScanCoverage Coverage,
    DateTime CreatedAt,
    DateTime? CompletedAt);

public record GetScanHistoryQuery(Guid DomainId, ScanStatus? Status, ScanCoverage? Coverage, string SortBy = "created_at",
    string Order = "asc", int Page = 1, int PageSize = 20)
    : IRequest<Result<PagedResult<ScanSummary>>>;

public record ScanFilter(
    Guid UserId,
    Guid? DomainId,
    ScanStatus? Status,
    ScanCoverage? Coverage,
    string SortBy,
    string Order,
    int Page,
    int PageSize);

public class GetScanHistoryHandler(
    IScanRepository scanRepo,
    IDomainRepository domainRepo,
    ICurrentUser currentUser,
    IHttpContextAccessor http)
    : IRequestHandler<GetScanHistoryQuery, Result<PagedResult<ScanSummary>>>
{
    public async Task<Result<PagedResult<ScanSummary>>> Handle(GetScanHistoryQuery query, CancellationToken ct)
    {
        var userId = currentUser.UserId;


        var domain = await domainRepo.FindUserDomainById(userId, query.DomainId, ct);
        if (domain is null)
            return Result<PagedResult<ScanSummary>>.Failure(Error.NotFound("Domain not found."));


        var filter = new ScanFilter(
            UserId: userId,
            DomainId: query.DomainId,
            Status: query.Status,
            Coverage: query.Coverage,
            SortBy: query.SortBy.ToLowerInvariant(),
            Order: query.Order.ToLowerInvariant(),
            Page: query.Page,
            PageSize: Math.Min(query.PageSize, 50));

        var (items, totalCount) = await scanRepo.GetPaged(filter, ct);

        var summaries = items.Select(s => new ScanSummary(
            s.Id,
            s.DomainId,
            s.Domain?.DomainName ?? string.Empty,
            s.Status,
            ClassifyRisk(s.SecurityScore),
            s.Coverage,
            s.CreatedAt,
            s.CompletedAt)).ToList();

        var ctx = http.HttpContext!;

        return Result<PagedResult<ScanSummary>>.Success(
            PagedResult<ScanSummary>.From(
                summaries,
                totalCount,
                filter.Page,
                filter.PageSize,
                ctx.Request.Path,
                ctx.Request.QueryString.ToString()));
    }

    private static string ClassifyRisk(int? score) => score switch
    {
        >= 80 => "Low",
        >= 60 => "High",
        _ => "Critical"
    };
}