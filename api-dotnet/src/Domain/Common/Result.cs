namespace Domain.Common;

/// <summary>
/// Wraps the outcome of an operation without throwing exceptions for expected failures.
/// A handler always returns either a <see cref="Value"/> or a typed <see cref="Error"/> —
/// no try/catch needed and the failure reason is always explicit.
/// <para>
/// Use <see cref="Success"/> when the operation worked, <see cref="Failure"/> when it didn't.
/// </para>
/// </summary>
/// <typeparam name="T">The type of the value returned on success.</typeparam>
public sealed class Result<T>
{
    /// <summary><c>true</c> if the operation succeeded; <c>false</c> if it failed.</summary>
    public bool IsSuccess { get; }

    /// <summary>The returned data when <see cref="IsSuccess"/> is <c>true</c>; otherwise <c>null</c>.</summary>
    public T? Value { get; }

    /// <summary>The failure details when <see cref="IsSuccess"/> is <c>false</c>; otherwise <c>null</c>.</summary>
    public Error? Error { get; }

    private Result(T value) { IsSuccess = true; Value = value; Error = null; }
    private Result(Error error) { IsSuccess = false; Value = default; Error = error; }

    /// <summary>Creates a successful result wrapping <paramref name="value"/>.</summary>
    public static Result<T> Success(T value) => new(value);

    /// <summary>
    /// Creates a failed result. Use the <see cref="Error"/> static helpers to build the error,
    /// e.g. <c>Error.NotFound("Scan not found")</c>.
    /// </summary>
    public static Result<T> Failure(Error error) => new(error);
}
