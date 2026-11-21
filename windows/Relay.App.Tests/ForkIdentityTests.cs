using System.Xml.Linq;
using Linqavo.Core;
using Xunit;

namespace Linqavo.App.Tests;
public class ForkIdentityTests
{
    [Fact] public void ForkIdentityAndVersionAreIndependent()
    {
        var xml = XDocument.Load(Path.Combine(SharedContracts.Dir, "LinqavoVersion.props"));
        string Value(string name) => xml.Descendants(name).Single().Value;
        Assert.Matches(@"^\d+\.\d+\.\d+$", Value("LinqavoVersion"));
        Assert.Equal(Value("LinqavoAssemblyVersion"), typeof(TunnelStats).Assembly.GetName().Version!.ToString());
        Assert.Equal(Value("LinqavoVersion"), System.Reflection.CustomAttributeExtensions
            .GetCustomAttribute<System.Reflection.AssemblyInformationalVersionAttribute>(typeof(TunnelStats).Assembly)!.InformationalVersion);
        Assert.Contains("Linqavo", PendingUpdate.DefaultDirectory);
        Assert.Equal("Linqavo", TunnelStats.DefaultAdapter);
    }
}
