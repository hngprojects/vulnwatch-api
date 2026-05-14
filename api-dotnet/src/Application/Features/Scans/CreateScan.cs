using MediatR;
using Domain.Common;
using Domain.Entities;
using Domain.Enums;
using Application.Interfaces;

namespace Application.Features.Scans;


// requestDto: What the API receives from the client (Intern-friendly: Shape of the incoming data)
public record CreateScanRequest(string Domain, string[] ScanTypes);

// Command: What the user wants to do (Intern-friendly: Data coming in) - this is what the handler will process
public record CreateScanCommand(Guid IdempotencyKey, string Domain, string[] ScanTypes)
    : IRequest<Result<ScanResponse>>;

// DTO: Data Transfer Object (Intern-friendly: Shape of the response)
public record ScanResponse(Guid ScanId, string Status);

// Handler: The "Brain" of the operation (Intern-friendly: Where the logic happens)
public class CreateScanHandler : IRequestHandler<CreateScanCommand, Result<ScanResponse>>
{
    private readonly IRedisProducer _redisProducer;

    public CreateScanHandler(IRedisProducer redisProducer)
    {
        _redisProducer = redisProducer;
    }

    public async Task<Result<ScanResponse>> Handle(CreateScanCommand request, CancellationToken cancellationToken)
    {
        // 1. Guard against duplicate submissions — same idempotency key means the client already sent this request
        // var existing = await _scanRepository.FindByIdempotencyKeyAsync(request.IdempotencyKey, cancellationToken);
        // if (existing is not null)
        //     return Result<ScanResponse>.Failure(Error.Conflict("A scan with this idempotency key was already submitted."));

        // 2. Build the scan entity
        var scan = Scan.Create(
            userId: Guid.Empty, // TODO: replace with authenticated user ID from ICurrentUserService 
            idempotencyKey: request.IdempotencyKey,
            targetType: ScanTargetType.Domain
        );

        // 3. TODO: Persist scan to DB before publishing so the record exists if the worker picks it up immediately
        // await _scanRepository.AddAsync(scan, cancellationToken);

        // 4. Push the job to Redis Queue for the scanner worker to pick up
        await _redisProducer.PublishAsync("scan-jobs", new
        {
            scan_id = scan.Id,
            domain = request.Domain,
            scan_types = request.ScanTypes,
            requested_by = scan.UserId, // TODO: will be the real user ID once auth is in place
        });

        // 5. Return the full result — controller maps IsSuccess/Error to the correct HTTP status code
        return Result<ScanResponse>.Success(new ScanResponse(scan.Id, scan.Status.ToString()));
    }
}
