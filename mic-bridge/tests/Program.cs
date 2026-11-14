using SpaceConnectMicBridge;

const string Key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
const string Id = "ICEiIyQlJicoKSorLC0uLw==";
byte[] frame = Convert.FromHexString("53434d32202122232425262728292a2b2c2d2e2f000000000100000000000000d185b9dd5747159ba1c35be5642b30ddda887286");
byte[] expectedAck = Convert.FromHexString("53434132202122232425262728292a2b2c2d2e2f000000000100000000000000018ded6296e8753c7fb540e56e32bd17207760c354332eb025dc863a07a6589c0d");
DateTimeOffset now = DateTimeOffset.Parse("2026-11-03T00:00:00Z");
using var session = new MicSession(Id, Key, now.AddMinutes(1));
var damaged = (byte[])frame.Clone();
damaged[^1] ^= 1;
Check(!session.TryRead(damaged, now, out _), "reject unauthenticated audio");
Check(session.TryRead(frame, now, out var pcm), "accept authenticated PCM after a forged packet");
Check(pcm.SequenceEqual(new byte[] { 0x34, 0x12, 0xcc, 0xed }), "recover the original recording samples");
Check(session.Acknowledge(frame).SequenceEqual(expectedAck), "authenticate host acknowledgement");
Check(!session.TryRead(frame, now, out _), "reject replayed audio");
using var expired = new MicSession(Id, Key, now.AddSeconds(-1));
Check(!expired.TryRead(frame, now, out _), "reject expired session");
using var other = new MicSession("AAAAAAAAAAAAAAAAAAAAAA==", Key, now.AddMinutes(1));
Check(!other.TryRead(frame, now, out _), "reject another machine session");
Console.WriteLine("7 microphone protocol checks passed");

static void Check(bool success, string description)
{
    if (!success) throw new InvalidOperationException(description);
}
